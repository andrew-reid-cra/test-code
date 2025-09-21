package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes live packet capture and protocol reconstruction, persisting message pairs in real time.
 * <p>The pipeline decouples capture from persistence via a bounded worker pool so back-pressure is
 * handled gracefully. Instances are not reusable; invoke {@link #run()} at most once.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class LiveProcessingUseCase {
  private static final Logger log = LoggerFactory.getLogger(LiveProcessingUseCase.class);

  private static final int DEFAULT_PERSISTENCE_WORKERS =
      Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
  private static final int DEFAULT_QUEUE_CAPACITY = DEFAULT_PERSISTENCE_WORKERS * 128;
  private static final long QUEUE_OFFER_TIMEOUT_MILLIS = 50L;

  private final PacketSource packetSource;
  private final FrameDecoder frameDecoder;
  private final FlowProcessingEngine flowEngine;
  private final PersistencePort persistence;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;

  private final int persistenceWorkerCount;
  private final BlockingQueue<PersistTask> persistenceQueue;
  private final List<Thread> persistenceWorkers = new ArrayList<>();
  private final AtomicReference<Exception> persistenceFailure = new AtomicReference<>();
  private final String workerThreadPrefix;

  private boolean workersStarted;

  /**
   * Creates the live processing pipeline by wiring capture, flow assembly, and persistence ports.
   *
   * @param packetSource source of raw frames; must support {@link PacketSource#start()} and {@link PacketSource#poll()}
   * @param frameDecoder converts frames to TCP segments when possible
   * @param flowAssembler orders TCP bytes per flow
   * @param protocolDetector detects protocols for new flows
   * @param reconstructorFactories factories for protocol-specific byte-to-message handlers
   * @param pairingFactories factories that convert message events to {@link MessagePair}s
   * @param persistence sink for emitting message pairs immediately
   * @param metrics metrics sink updated for capture, decode, and pairing events
   * @param enabledProtocols subset of protocols to process; others are ignored
   * @since RADAR 0.1-doc
   */
  public LiveProcessingUseCase(
      PacketSource packetSource,
      FrameDecoder frameDecoder,
      FlowAssembler flowAssembler,
      ProtocolDetector protocolDetector,
      Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories,
      Map<ProtocolId, Supplier<PairingEngine>> pairingFactories,
      PersistencePort persistence,
      MetricsPort metrics,
      Set<ProtocolId> enabledProtocols) {
    this(
        packetSource,
        frameDecoder,
        flowAssembler,
        protocolDetector,
        reconstructorFactories,
        pairingFactories,
        persistence,
        metrics,
        enabledProtocols,
        DEFAULT_PERSISTENCE_WORKERS,
        DEFAULT_QUEUE_CAPACITY);
  }

  /**
   * Creates the live processing pipeline with explicit persistence worker settings.
   *
   * @param packetSource source of raw frames
   * @param frameDecoder converts frames to TCP segments
   * @param flowAssembler orders TCP bytes per flow
   * @param protocolDetector detects protocols for new flows
   * @param reconstructorFactories factories for protocol-specific byte-to-message handlers
   * @param pairingFactories factories that convert message events to {@link MessagePair}s
   * @param persistence sink for emitting message pairs immediately
   * @param metrics metrics sink updated for capture, decode, and pairing events
   * @param enabledProtocols subset of protocols to process; others are ignored
   * @param persistenceWorkers number of worker threads handling persistence
   * @param persistenceQueueCapacity maximum buffered message pairs awaiting persistence
   * @since RADAR 0.1-doc
   */
  public LiveProcessingUseCase(
      PacketSource packetSource,
      FrameDecoder frameDecoder,
      FlowAssembler flowAssembler,
      ProtocolDetector protocolDetector,
      Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories,
      Map<ProtocolId, Supplier<PairingEngine>> pairingFactories,
      PersistencePort persistence,
      MetricsPort metrics,
      Set<ProtocolId> enabledProtocols,
      int persistenceWorkers,
      int persistenceQueueCapacity) {
    this.packetSource = Objects.requireNonNull(packetSource, "packetSource");
    this.frameDecoder = Objects.requireNonNull(frameDecoder, "frameDecoder");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.enabledProtocols = Set.copyOf(Objects.requireNonNull(enabledProtocols, "enabledProtocols"));
    this.persistenceWorkerCount = Math.max(1, persistenceWorkers);
    int queueCapacity = Math.max(this.persistenceWorkerCount, persistenceQueueCapacity);
    this.persistenceQueue = new ArrayBlockingQueue<>(queueCapacity);
    this.workerThreadPrefix = "live-persist-" + Integer.toHexString(System.identityHashCode(this));
    this.flowEngine =
        new FlowProcessingEngine(
            "live",
            Objects.requireNonNull(flowAssembler, "flowAssembler"),
            Objects.requireNonNull(protocolDetector, "protocolDetector"),
            Objects.requireNonNull(reconstructorFactories, "reconstructorFactories"),
            Objects.requireNonNull(pairingFactories, "pairingFactories"),
            this.metrics,
            this.enabledProtocols);
  }

  /**
   * Runs the live loop until interrupted, persisting each reconstructed message pair via the worker pool.
   *
   * @throws Exception if any port fails during capture, decode, or persistence
   * @since RADAR 0.1-doc
   */
  public void run() throws Exception {
    MDC.put("pipeline", "live");
    try {
      boolean started = false;
      long frameCount = 0;
      long pairCount = 0;
      startPersistenceWorkers();
      Exception primaryFailure = null;
      try {
        packetSource.start();
        started = true;
        log.info("Live pipeline packet source started with {} persistence workers", persistenceWorkerCount);

        while (!Thread.currentThread().isInterrupted()) {
          if (persistenceFailure.get() != null) {
            primaryFailure = persistenceFailure.get();
            break;
          }

          Optional<RawFrame> maybeFrame;
          try {
            maybeFrame = packetSource.poll();
          } catch (Exception ex) {
            if (ex instanceof InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
            throw ex;
          }

          if (maybeFrame.isEmpty()) {
            continue;
          }

          frameCount++;
          Optional<TcpSegment> maybeSegment = frameDecoder.decode(maybeFrame.get());
          if (maybeSegment.isEmpty()) {
            metrics.increment("live.segment.skipped.decode");
            continue;
          }

          List<MessagePair> pairs = flowEngine.onSegment(maybeSegment.get());

          if (pairs.isEmpty()) {
            continue;
          }

          for (MessagePair pair : pairs) {
            if (Thread.currentThread().isInterrupted()) {
              break;
            }
            if (persistenceFailure.get() != null) {
              primaryFailure = persistenceFailure.get();
              break;
            }
            try {
              enqueueForPersistence(pair);
              pairCount++;
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }

        if (primaryFailure == null) {
          primaryFailure = persistenceFailure.get();
        }
        if (primaryFailure == null) {
          try {
            flushPersistence();
            log.info("Live pipeline flushed persistence queue after {} frames and {} pairs", frameCount, pairCount);
          } catch (Exception flushFailure) {
            primaryFailure = flushFailure;
          }
        }
      } catch (Exception runFailure) {
        primaryFailure = runFailure;
      } finally {
        shutdownPersistenceWorkers();
        try {
          flowEngine.close();
          log.info("Live flow engine closed");
        } catch (Exception flowCloseFailure) {
          log.error("Failed to close live flow engine", flowCloseFailure);
          if (primaryFailure == null) {
            primaryFailure = flowCloseFailure;
          }
        }
        try {
          persistence.close();
          log.info("Live persistence port closed");
        } catch (Exception persistenceCloseFailure) {
          log.error("Failed to close live persistence port", persistenceCloseFailure);
          if (primaryFailure == null) {
            primaryFailure = persistenceCloseFailure;
          }
        }
        if (started) {
          try {
            packetSource.close();
            log.info("Live packet source closed");
          } catch (Exception packetSourceCloseFailure) {
            log.error("Failed to close live packet source", packetSourceCloseFailure);
            if (primaryFailure == null) {
              primaryFailure = packetSourceCloseFailure;
            }
          }
        }
      }

      if (primaryFailure != null) {
        log.debug("Live pipeline terminating after {} frames and {} pairs due to failure", frameCount, pairCount);
        throw primaryFailure;
      }
      log.info("Live pipeline completed; processed {} frames and emitted {} pairs", frameCount, pairCount);
    } finally {
      MDC.remove("pipeline");
    }
  }

  private void startPersistenceWorkers() {
    if (workersStarted) {
      throw new IllegalStateException("Live processing already running");
    }
    workersStarted = true;
    persistenceFailure.set(null);
    log.info("Starting {} persistence workers with queue capacity {}", persistenceWorkerCount, persistenceQueue.remainingCapacity());

    for (int i = 0; i < persistenceWorkerCount; i++) {
      Thread worker =
          new Thread(
              () -> {
                while (true) {
                  PersistTask task;
                  try {
                    task = persistenceQueue.take();
                  } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                  if (task.poison) {
                    break;
                  }
                  try {
                    persistence.persist(task.pair);
                    metrics.increment("live.pairs.persisted");
                  } catch (Exception ex) {
                    metrics.increment("live.persist.error");
                    if (persistenceFailure.compareAndSet(null, ex)) {
                      log.error("Persistence worker {} failed", Thread.currentThread().getName(), ex);
                    }
                    break;
                  }
                }
              },
              workerThreadPrefix + "-" + i);
      worker.setDaemon(true);
      worker.start();
      persistenceWorkers.add(worker);
    }
  }

  private void enqueueForPersistence(MessagePair pair) throws InterruptedException {
    PersistTask task = PersistTask.payload(pair);
    while (persistenceFailure.get() == null) {
      if (persistenceQueue.offer(task, QUEUE_OFFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        metrics.increment("live.persist.enqueued");
        return;
      }
    }
  }

  private void flushPersistence() throws Exception {
    try {
      persistence.flush();
    } catch (Exception ex) {
      metrics.increment("live.persist.flush.error");
      throw ex;
    }
  }

  private void shutdownPersistenceWorkers() {
    if (!workersStarted) {
      return;
    }

    log.info("Signalling persistence workers to stop");

    for (int i = 0; i < persistenceWorkerCount; i++) {
      boolean enqueued = false;
      while (!enqueued) {
        try {
          if (persistenceQueue.offer(PersistTask.POISON, QUEUE_OFFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            enqueued = true;
          } else if (!anyWorkerAlive()) {
            enqueued = true;
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          enqueued = true;
        }
      }
    }

    for (Thread worker : persistenceWorkers) {
      try {
        worker.join(TimeUnit.SECONDS.toMillis(5));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    log.info("Persistence workers joined");
    persistenceWorkers.clear();
    persistenceQueue.clear();
    workersStarted = false;
  }

  private boolean anyWorkerAlive() {
    for (Thread worker : persistenceWorkers) {
      if (worker.isAlive()) {
        return true;
      }
    }
    return false;
  }

  private static final class PersistTask {
    private static final PersistTask POISON = new PersistTask(null, true);

    private final MessagePair pair;
    private final boolean poison;

    private PersistTask(MessagePair pair, boolean poison) {
      this.pair = pair;
      this.poison = poison;
    }

    static PersistTask payload(MessagePair pair) {
      return new PersistTask(Objects.requireNonNull(pair, "pair"), false);
    }
  }
}
