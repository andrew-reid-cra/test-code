package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveProcessingUseCasePersistenceTest {

  private RecordingMetrics metrics;
  private LiveProcessingUseCase useCase;

  @BeforeEach
  void init() {
    metrics = new RecordingMetrics();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (useCase != null) {
      LiveReflection.shutdown(useCase);
      useCase = null;
    }
  }

  @Test
  void lifecycleStopsGracefully() throws Exception {
    CountingPersistence persistence = new CountingPersistence();
    useCase = newUseCase(2, 8, persistence, metrics);

    LiveReflection.start(useCase);
    int total = 24;
    for (int i = 0; i < total; i++) {
      LiveReflection.enqueue(useCase, samplePair(i));
    }
    awaitCount(persistence.counter(), total, Duration.ofSeconds(2));
    LiveReflection.shutdown(useCase);

    assertEquals(total, persistence.counter().get());
    List<Long> workerActive = metrics.observed("live.persist.worker.active");
    assertFalse(workerActive.isEmpty());
    assertEquals(0L, workerActive.get(workerActive.size() - 1));
    assertTrue(metrics.hasObservation("live.persist.queue.highWater"));
  }

  @Test
  void executorStopsAfterShutdown() throws Exception {
    CountingPersistence persistence = new CountingPersistence();
    useCase = newUseCase(2, 8, persistence, metrics);

    LiveReflection.start(useCase);
    LiveReflection.enqueue(useCase, samplePair(0));
    ExecutorService executor = LiveReflection.executor(useCase);
    assertNotNull(executor, "persistence executor should be available");

    LiveReflection.shutdown(useCase);

    assertTrue(executor.isShutdown(), "persistence executor should shut down");
    assertTrue(
        executor.awaitTermination(2, TimeUnit.SECONDS),
        "persistence executor should terminate cleanly");
  }

  @Test
  void backpressureDropsIncrementMetric() throws Exception {
    SlowPersistence persistence = new SlowPersistence(75);
    useCase = newUseCase(1, 1, persistence, metrics);

    LiveReflection.start(useCase);

    LiveReflection.enqueue(useCase, samplePair(0));
    LiveReflection.enqueue(useCase, samplePair(1));
    IllegalStateException saturation =
        assertThrows(IllegalStateException.class, () -> LiveReflection.enqueue(useCase, samplePair(2)));
    assertEquals("Persistence queue saturated", saturation.getMessage());

    awaitMetric("live.persist.enqueue.dropped", 1, Duration.ofSeconds(1));
    assertTrue(metrics.count("live.persist.enqueue.retry") >= 1);
    assertTrue(LiveReflection.stopRequested(useCase));
    assertNotNull(LiveReflection.failure(useCase));
  }

  @Test
  void workerFailurePropagatesAndStopsPipeline() throws Exception {
    FailingPersistence persistence = new FailingPersistence(1);
    useCase = newUseCase(2, 8, persistence, metrics);

    LiveReflection.start(useCase);
    LiveReflection.enqueue(useCase, samplePair(0));
    awaitFailure(useCase, Duration.ofSeconds(2));
    awaitMetric("live.persist.error", 1, Duration.ofSeconds(1));
    awaitMetric("live.persist.worker.uncaught", 1, Duration.ofSeconds(1));

    Exception failure = LiveReflection.failure(useCase);
    assertNotNull(failure);
    assertTrue(failure instanceof IllegalStateException);
    assertThrows(IllegalStateException.class, () -> LiveReflection.enqueue(useCase, samplePair(1)));
  }

  @Test
  void interruptingWorkersSurfaceMetrics() throws Exception {
    CountingPersistence persistence = new CountingPersistence();
    useCase = newUseCase(2, 8, persistence, metrics);

    LiveReflection.start(useCase);
    ThreadPoolExecutor executor = (ThreadPoolExecutor) LiveReflection.executor(useCase);
    executor.shutdownNow();
    executor.awaitTermination(1, TimeUnit.SECONDS);

    assertTrue(metrics.count("live.persist.worker.interrupted") >= 1);
  }

  @Test
  void metricsCapturedForSuccessPath() throws Exception {
    CountingPersistence persistence = new CountingPersistence();
    useCase = newUseCase(2, 8, persistence, metrics);

    LiveReflection.start(useCase);
    for (int i = 0; i < 12; i++) {
      LiveReflection.enqueue(useCase, samplePair(i));
    }
    awaitCount(persistence.counter(), 12, Duration.ofSeconds(2));
    LiveReflection.shutdown(useCase);

    assertEquals(12, metrics.count("live.pairs.persisted"));
    assertFalse(metrics.observed("live.persist.enqueue.waitNanos").isEmpty());
    assertFalse(metrics.observed("live.persist.latencyNanos").isEmpty());
    assertTrue(metrics.hasObservation("live.persist.queue.depth"));
  }

  private LiveProcessingUseCase newUseCase(
      int workers, int capacity, PersistencePort persistencePort, MetricsPort metricsPort) {
    PacketSource packetSource = new NoOpPacketSource();
    FrameDecoder frameDecoder = frame -> Optional.empty();
    FlowAssembler flowAssembler = segment -> Optional.empty();
    ProtocolDetector protocolDetector = (flow, port, bytes) -> ProtocolId.UNKNOWN;
    Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories = Map.of();
    Map<ProtocolId, Supplier<PairingEngine>> pairingFactories = Map.of();
    LiveProcessingUseCase.PersistenceSettings settings =
        new LiveProcessingUseCase.PersistenceSettings(workers, capacity, LiveProcessingUseCase.QueueType.ARRAY);
    return new LiveProcessingUseCase(
        packetSource,
        frameDecoder,
        flowAssembler,
        protocolDetector,
        reconstructorFactories,
        pairingFactories,
        persistencePort,
        metricsPort,
        Set.of(ProtocolId.HTTP),
        settings);
  }

  private MessagePair samplePair(int idx) {
    byte[] payload = ("payload-" + idx).getBytes();
    FiveTuple flow = new FiveTuple("10.0.0.1", 1010 + idx, "10.0.0.2", 8080, "TCP");
    ByteStream stream = new ByteStream(flow, true, payload, System.nanoTime());
    MessageEvent request = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, stream, MessageMetadata.empty());
    MessageEvent response = new MessageEvent(ProtocolId.HTTP, MessageType.RESPONSE, stream, MessageMetadata.empty());
    return new MessagePair(request, response);
  }

  private void awaitMetric(String key, int expected, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (metrics.count(key) < expected && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(5);
    }
    assertTrue(metrics.count(key) >= expected, key + " not observed");
  }

  private static void awaitCount(AtomicInteger counter, int expected, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (counter.get() < expected && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(5);
    }
    assertTrue(counter.get() >= expected, "persistence did not reach expected count");
  }

  private static void awaitFailure(LiveProcessingUseCase useCase, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (LiveReflection.failure(useCase) == null && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(5);
    }
    assertNotNull(LiveReflection.failure(useCase), "persistence failure not observed");
  }

  private static final class CountingPersistence implements PersistencePort {
    private final AtomicInteger count = new AtomicInteger();

    @Override
    public void persist(MessagePair pair) {
      count.incrementAndGet();
    }

    AtomicInteger counter() {
      return count;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private static final class SlowPersistence implements PersistencePort {
    private final AtomicInteger count = new AtomicInteger();
    private final long sleepMillis;

    SlowPersistence(long sleepMillis) {
      this.sleepMillis = sleepMillis;
    }

    @Override
    public void persist(MessagePair pair) {
      try {
        TimeUnit.MILLISECONDS.sleep(sleepMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      count.incrementAndGet();
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private static final class FailingPersistence implements PersistencePort {
    private final AtomicInteger count = new AtomicInteger();
    private final int failOn;

    FailingPersistence(int failOn) {
      this.failOn = failOn;
    }

    @Override
    public void persist(MessagePair pair) {
      if (count.incrementAndGet() >= failOn) {
        throw new IllegalStateException("forced failure");
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private static final class NoOpPacketSource implements PacketSource {
    @Override
    public void start() {}

    @Override
    public Optional<RawFrame> poll() {
      return Optional.empty();
    }

    @Override
    public void close() {}
  }

  private static final class RecordingMetrics implements MetricsPort {
    private final Map<String, AtomicInteger> counters = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<Long>> observations = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void increment(String key) {
      counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void observe(String key, long value) {
      observations.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(value);
    }

    int count(String key) {
      return counters.getOrDefault(key, new AtomicInteger()).get();
    }

    List<Long> observed(String key) {
      return observations.getOrDefault(key, Collections.emptyList());
    }

    boolean hasObservation(String key) {
      return observations.containsKey(key);
    }
  }

  private static final class LiveReflection {
    private static final Method START = method("startPersistenceWorkers");
    private static final Method ENQUEUE = method("enqueueForPersistence", MessagePair.class);
    private static final Method SHUTDOWN = method("shutdownPersistenceWorkers");
    private static final Field FAILURE = field("persistenceFailure");
    private static final Field EXECUTOR = field("persistenceExecutor");
    private static final Field STOP_FLAG = field("persistenceStopRequested");

    private static Method method(String name, Class<?>... types) {
      try {
        Method method = LiveProcessingUseCase.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
      } catch (NoSuchMethodException ex) {
        throw new IllegalStateException(ex);
      }
    }

    private static Field field(String name) {
      try {
        Field field = LiveProcessingUseCase.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ex) {
        throw new IllegalStateException(ex);
      }
    }

    static void start(LiveProcessingUseCase useCase) {
      try {
        START.invoke(useCase);
      } catch (IllegalAccessException | InvocationTargetException ex) {
        throw new IllegalStateException(ex);
      }
    }

    static void enqueue(LiveProcessingUseCase useCase, MessagePair pair) throws Exception {
      try {
        ENQUEUE.invoke(useCase, pair);
      } catch (InvocationTargetException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof InterruptedException interrupted) {
          throw interrupted;
        }
        if (cause instanceof Exception exception) {
          throw exception;
        }
        throw new RuntimeException(cause);
      }
    }

    static void shutdown(LiveProcessingUseCase useCase) throws Exception {
      try {
        SHUTDOWN.invoke(useCase);
      } catch (InvocationTargetException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof Exception exception) {
          throw exception;
        }
        throw new RuntimeException(cause);
      }
    }

    static Exception failure(LiveProcessingUseCase useCase) {
      try {
        @SuppressWarnings("unchecked")
        AtomicReference<Exception> ref = (AtomicReference<Exception>) FAILURE.get(useCase);
        return ref.get();
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException(ex);
      }
    }

    static ExecutorService executor(LiveProcessingUseCase useCase) {
      try {
        return (ExecutorService) EXECUTOR.get(useCase);
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException(ex);
      }
    }

    static boolean stopRequested(LiveProcessingUseCase useCase) {
      try {
        return ((AtomicBoolean) STOP_FLAG.get(useCase)).get();
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }
}
