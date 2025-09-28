package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import ca.gc.cra.radar.domain.msg.TransactionId;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;

final class LiveProcessingUseCaseTest {
  private static final Logger LIVE_LOGGER =
      (Logger) LoggerFactory.getLogger(LiveProcessingUseCase.class);
  private static Level originalLevel;
  private static boolean originalAdditive;

  @BeforeAll
  static void suppressLiveProcessingLogs() {
    originalLevel = LIVE_LOGGER.getLevel();
    originalAdditive = LIVE_LOGGER.isAdditive();
    LIVE_LOGGER.setAdditive(false);
    LIVE_LOGGER.setLevel(Level.OFF);
  }

  @AfterAll
  static void restoreLiveProcessingLogs() {
    LIVE_LOGGER.setLevel(originalLevel);
    LIVE_LOGGER.setAdditive(originalAdditive);
  }

  @Test
  void persistsHttpRequestResponsePair() throws Exception {
    byte[] requestBytes = "GET / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII);
    byte[] responseBytes = "HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII);

    FiveTuple clientToServer = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
    FiveTuple serverToClient = new FiveTuple("10.0.0.2", 80, "10.0.0.1", 1234, "TCP");

    TcpSegment requestSegment =
        new TcpSegment(clientToServer, 1L, true, requestBytes, false, false, false, false, true, 10L);
    TcpSegment responseSegment =
        new TcpSegment(serverToClient, 2L, false, responseBytes, true, false, false, false, true, 20L);

    StubPacketSource packetSource =
        new StubPacketSource(List.of(new RawFrame(new byte[0], 0), new RawFrame(new byte[0], 1)));
    StubFrameDecoder frameDecoder = new StubFrameDecoder(List.of(requestSegment, responseSegment));
    FlowAssembler flowAssembler = new EchoFlowAssembler();

    Set<ProtocolId> enabledProtocols = Set.of(ProtocolId.HTTP);
    ProtocolDetector detector = new StubProtocolDetector();

    Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories =
        Map.of(ProtocolId.HTTP, StubMessageReconstructor::new);
    Map<ProtocolId, Supplier<PairingEngine>> pairingFactories =
        Map.of(ProtocolId.HTTP, StubPairingEngine::new);

    RecordingPersistence persistence = new RecordingPersistence();

    LiveProcessingUseCase useCase =
        new LiveProcessingUseCase(
            packetSource,
            frameDecoder,
            flowAssembler,
            detector,
            reconstructorFactories,
            pairingFactories,
            persistence,
            MetricsPort.NO_OP,
            enabledProtocols,
            2,
            4);

    Thread worker =
        new Thread(
            () -> {
              try {
                useCase.run();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            "live-processing-test");

    try {
      worker.start();
      assertTrue(persistence.awaitPairs(1, 2, TimeUnit.SECONDS), "expected one persisted pair");

      List<MessagePair> persistedPairs = persistence.snapshot();
      MessagePair pair = persistedPairs.get(0);
      assertEquals(ProtocolId.HTTP, pair.request().protocol());
      assertNotNull(pair.request().metadata().transactionId());
      assertEquals(pair.request().metadata().transactionId(), pair.response().metadata().transactionId());
      assertEquals(MessageType.REQUEST, pair.request().type());
      assertEquals(
          "GET / HTTP/1.1\r\n",
          new String(pair.request().payload().data(), StandardCharsets.US_ASCII));
      assertEquals(10L, pair.request().payload().timestampMicros());
      assertEquals(MessageType.RESPONSE, pair.response().type());
      assertEquals(
          "HTTP/1.1 200 OK\r\n",
          new String(pair.response().payload().data(), StandardCharsets.US_ASCII));
      assertEquals(20L, pair.response().payload().timestampMicros());
      assertTrue(
          persistence.workerThreads().stream().allMatch(name -> name.startsWith("live-persist-")),
          "expected persistence to run on pool threads");
    } finally {
      worker.interrupt();
      worker.join(2000);
    }
  }

  @Test
  void persistenceFailureSignalsMetricsAndFailsRun() throws Exception {
    byte[] requestBytes = "GET / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII);
    byte[] responseBytes = "HTTP/1.1 500 Internal Server Error\r\n".getBytes(StandardCharsets.US_ASCII);

    FiveTuple clientToServer = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
    FiveTuple serverToClient = new FiveTuple("10.0.0.2", 80, "10.0.0.1", 1234, "TCP");

    TcpSegment requestSegment =
        new TcpSegment(clientToServer, 1L, true, requestBytes, false, false, false, false, true, 10L);
    TcpSegment responseSegment =
        new TcpSegment(serverToClient, 2L, false, responseBytes, true, false, false, false, true, 20L);

    StubPacketSource packetSource =
        new StubPacketSource(List.of(new RawFrame(new byte[0], 0), new RawFrame(new byte[0], 1)));
    StubFrameDecoder frameDecoder = new StubFrameDecoder(List.of(requestSegment, responseSegment));
    FlowAssembler flowAssembler = new EchoFlowAssembler();

    Set<ProtocolId> enabledProtocols = Set.of(ProtocolId.HTTP);
    ProtocolDetector detector = new StubProtocolDetector();

    Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories =
        Map.of(ProtocolId.HTTP, StubMessageReconstructor::new);
    Map<ProtocolId, Supplier<PairingEngine>> pairingFactories =
        Map.of(ProtocolId.HTTP, StubPairingEngine::new);

    RecordingMetrics metrics = new RecordingMetrics();
    FailingPersistence persistence = new FailingPersistence();

    LiveProcessingUseCase useCase =
        new LiveProcessingUseCase(
            packetSource,
            frameDecoder,
            flowAssembler,
            detector,
            reconstructorFactories,
            pairingFactories,
            persistence,
            metrics,
            enabledProtocols,
            2,
            4);

    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread worker =
        new Thread(
            () -> {
              try {
                useCase.run();
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                finished.countDown();
              }
            },
            "live-processing-failure");

    try {
      worker.start();
      assertTrue(finished.await(5, TimeUnit.SECONDS), "pipeline should terminate on failure");

      Throwable thrown = failure.get();
      assertNotNull(thrown, "run() should surface the persistence failure");
      assertTrue(thrown instanceof IllegalStateException);
      assertEquals("Simulated persistence failure", thrown.getMessage());
      assertEquals(1, metrics.count("live.persist.error"));
      assertEquals(1, metrics.count("live.persist.worker.uncaught"));
      assertEquals(1, persistence.attempts());
    } finally {
      worker.interrupt();
      worker.join(2000);
    }
  }

  @Test
  void slowPersistenceRaisesQueueMetrics() throws Exception {
    byte[] payload = "PING".getBytes(StandardCharsets.US_ASCII);

    FiveTuple clientToServer = new FiveTuple("10.0.0.10", 4000, "10.0.0.20", 80, "TCP");
    FiveTuple serverToClient = new FiveTuple("10.0.0.20", 80, "10.0.0.10", 4000, "TCP");

    List<RawFrame> frames =
        List.of(
            new RawFrame(new byte[0], 0),
            new RawFrame(new byte[0], 1),
            new RawFrame(new byte[0], 2),
            new RawFrame(new byte[0], 3),
            new RawFrame(new byte[0], 4),
            new RawFrame(new byte[0], 5));

    List<TcpSegment> segments =
        List.of(
            new TcpSegment(clientToServer, 1L, true, payload, false, false, false, false, true, 10L),
            new TcpSegment(serverToClient, 2L, false, payload, true, false, false, false, true, 20L),
            new TcpSegment(clientToServer, 3L, true, payload, false, false, false, false, true, 30L),
            new TcpSegment(serverToClient, 4L, false, payload, true, false, false, false, true, 40L),
            new TcpSegment(clientToServer, 5L, true, payload, false, false, false, false, true, 50L),
            new TcpSegment(serverToClient, 6L, false, payload, true, false, false, false, true, 60L));

    StubPacketSource packetSource = new StubPacketSource(frames);
    StubFrameDecoder frameDecoder = new StubFrameDecoder(segments);
    FlowAssembler flowAssembler = new EchoFlowAssembler();

    Set<ProtocolId> enabledProtocols = Set.of(ProtocolId.HTTP);
    ProtocolDetector detector = new StubProtocolDetector();

    Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories =
        Map.of(ProtocolId.HTTP, StubMessageReconstructor::new);
    Map<ProtocolId, Supplier<PairingEngine>> pairingFactories =
        Map.of(ProtocolId.HTTP, StubPairingEngine::new);

    RecordingMetrics metrics = new RecordingMetrics();
    RecordingPersistence persistence = new RecordingPersistence(5L);

    LiveProcessingUseCase useCase =
        new LiveProcessingUseCase(
            packetSource,
            frameDecoder,
            flowAssembler,
            detector,
            reconstructorFactories,
            pairingFactories,
            persistence,
            metrics,
            enabledProtocols,
            1,
            2);

    Thread worker =
        new Thread(
            () -> {
              try {
                useCase.run();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            "live-processing-backpressure");

    try {
      worker.start();
      assertTrue(persistence.awaitPairs(3, 5, TimeUnit.SECONDS), "expected three persisted pairs");

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (metrics.observed("live.persist.queue.highWater").isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }

      List<Long> highWaterSamples = metrics.observed("live.persist.queue.highWater");
      assertFalse(highWaterSamples.isEmpty(), "expected queue high water metric");
      long maxDepth = highWaterSamples.get(highWaterSamples.size() - 1);
      assertTrue(maxDepth >= 2, "high water mark should reach queue capacity");

      assertEquals(0, metrics.count("live.persist.enqueue.dropped"));
    } finally {
      worker.interrupt();
      worker.join(2000);
    }

    assertFalse(worker.isAlive(), "persistence thread should terminate after interrupt");
  }



  @Test
  void processesMultiplePairsWithBoundedQueue() throws Exception {
    byte[] requestOne = "GET /one HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII);
    byte[] responseOne = "HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII);
    byte[] requestTwo = "GET /two HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII);
    byte[] responseTwo = "HTTP/1.1 201 Created\r\n".getBytes(StandardCharsets.US_ASCII);

    FiveTuple clientToServer = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
    FiveTuple serverToClient = new FiveTuple("10.0.0.2", 80, "10.0.0.1", 1234, "TCP");

    TcpSegment segment1 =
        new TcpSegment(clientToServer, 1L, true, requestOne, false, false, false, false, true, 10L);
    TcpSegment segment2 =
        new TcpSegment(serverToClient, 2L, false, responseOne, false, false, false, false, true, 20L);
    TcpSegment segment3 =
        new TcpSegment(clientToServer, 3L, true, requestTwo, false, false, false, false, true, 30L);
    TcpSegment segment4 =
        new TcpSegment(serverToClient, 4L, false, responseTwo, true, false, false, false, true, 40L);

    StubPacketSource packetSource =
        new StubPacketSource(
            List.of(
                new RawFrame(new byte[0], 0),
                new RawFrame(new byte[0], 1),
                new RawFrame(new byte[0], 2),
                new RawFrame(new byte[0], 3)));
    StubFrameDecoder frameDecoder = new StubFrameDecoder(List.of(segment1, segment2, segment3, segment4));
    FlowAssembler flowAssembler = new EchoFlowAssembler();

    Set<ProtocolId> enabledProtocols = Set.of(ProtocolId.HTTP);
    ProtocolDetector detector = new StubProtocolDetector();

    Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories =
        Map.of(ProtocolId.HTTP, StubMessageReconstructor::new);
    Map<ProtocolId, Supplier<PairingEngine>> pairingFactories =
        Map.of(ProtocolId.HTTP, StubPairingEngine::new);

    RecordingPersistence persistence = new RecordingPersistence(25);

    LiveProcessingUseCase useCase =
        new LiveProcessingUseCase(
            packetSource,
            frameDecoder,
            flowAssembler,
            detector,
            reconstructorFactories,
            pairingFactories,
            persistence,
            MetricsPort.NO_OP,
            enabledProtocols,
            3,
            1);

    Thread worker =
        new Thread(
            () -> {
              try {
                useCase.run();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            "live-processing-test-parallel");

    try {
      worker.start();
      assertTrue(persistence.awaitPairs(2, 3, TimeUnit.SECONDS), "expected two persisted pairs");

      List<MessagePair> persistedPairs = persistence.snapshot();
      assertEquals(2, persistedPairs.size(), "should retain both reconstructed pairs");
      assertTrue(
          persistence.workerThreads().stream().allMatch(name -> name.startsWith("live-persist-")),
          "expected worker pool threads to persist pairs");
      assertTrue(
          persistence.workerThreads().size() >= 2,
          "expected at least two worker threads to handle persistence");
    } finally {
      worker.interrupt();
      worker.join(2000);
    }
  }

  private static final class StubProtocolDetector implements ProtocolDetector {
    @Override
    public ProtocolId classify(FiveTuple flow, int serverPort, byte[] signature) {
      return ProtocolId.HTTP;
    }
  }

  private static final class StubPacketSource implements PacketSource {
    private final Queue<RawFrame> frames;
    private final AtomicBoolean drained = new AtomicBoolean(false);

    StubPacketSource(List<RawFrame> frames) {
      this.frames = new ArrayDeque<>(frames);
    }

    @Override
    public void start() {}

    @Override
    public Optional<RawFrame> poll() throws InterruptedException {
      RawFrame next = frames.poll();
      if (next != null) {
        return Optional.of(next);
      }
      if (drained.compareAndSet(false, true)) {
        // signal once the capture has delivered all frames
      }
      Thread.sleep(10);
      return Optional.empty();
    }

    @Override
    public void close() {}
  }

  private static final class StubFrameDecoder implements FrameDecoder {
    private final Queue<TcpSegment> segments;

    StubFrameDecoder(List<TcpSegment> segments) {
      this.segments = new ArrayDeque<>(segments);
    }

    @Override
    public Optional<TcpSegment> decode(RawFrame frame) {
      return Optional.ofNullable(segments.poll());
    }
  }

  private static final class EchoFlowAssembler implements FlowAssembler {
    @Override
    public Optional<ByteStream> accept(TcpSegment segment) {
      if (segment.payload().length == 0) {
        return Optional.empty();
      }
      return Optional.of(
          new ByteStream(segment.flow(), segment.fromClient(), segment.payload(), segment.timestampMicros()));
    }
  }

  private static final class StubMessageReconstructor implements MessageReconstructor {
    private final Deque<String> pendingIds = new ArrayDeque<>();

    @Override
    public void onStart() {
      pendingIds.clear();
    }

    @Override
    public List<MessageEvent> onBytes(ByteStream slice) {
      boolean fromClient = slice.fromClient();
      String transactionId;
      if (fromClient) {
        transactionId = TransactionId.newId();
        pendingIds.addLast(transactionId);
      } else {
        transactionId = pendingIds.isEmpty() ? TransactionId.newId() : pendingIds.removeFirst();
      }
      MessageMetadata metadata = new MessageMetadata(transactionId, Map.of());
      MessageType type = fromClient ? MessageType.REQUEST : MessageType.RESPONSE;
      MessageEvent event = new MessageEvent(ProtocolId.HTTP, type, slice, metadata);
      return List.of(event);
    }

    @Override
    public void onClose() {
      pendingIds.clear();
    }
  }

  private static final class StubPairingEngine implements PairingEngine {
    private MessageEvent pending;

    @Override
    public Optional<MessagePair> accept(MessageEvent event) {
      Objects.requireNonNull(event, "event");
      if (event.type() == MessageType.REQUEST) {
        pending = event;
        return Optional.empty();
      }
      if (pending == null) {
        return Optional.empty();
      }
      MessagePair pair = new MessagePair(pending, event);
      pending = null;
      return Optional.of(pair);
    }
  }

  private static final class RecordingMetrics implements MetricsPort {
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Long>> observations = new ConcurrentHashMap<>();

    @Override
    public void increment(String key) {
      counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void observe(String key, long value) {
      observations.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(value);
    }

    int count(String key) {
      AtomicInteger counter = counters.get(key);
      return counter == null ? 0 : counter.get();
    }

    List<Long> observed(String key) {
      CopyOnWriteArrayList<Long> values = observations.get(key);
      return values == null ? List.of() : new ArrayList<>(values);
    }
  }

  private static final class FailingPersistence implements PersistencePort {
    private final AtomicInteger attempts = new AtomicInteger();

    @Override
    public void persist(MessagePair pair) {
      attempts.incrementAndGet();
      throw new IllegalStateException("Simulated persistence failure");
    }

    @Override
    public void close() {}

    int attempts() {
      return attempts.get();
    }
  }

  private static final class RecordingPersistence implements PersistencePort {
    private final Object monitor = new Object();
    private final List<MessagePair> pairs = new ArrayList<>();
    private final Set<String> workerThreads = ConcurrentHashMap.newKeySet();
    private final long delayMillis;

    RecordingPersistence() {
      this(0L);
    }

    RecordingPersistence(long delayMillis) {
      this.delayMillis = delayMillis;
    }

    @Override
    public void persist(MessagePair pair) {
      workerThreads.add(Thread.currentThread().getName());
      if (delayMillis > 0) {
        try {
          Thread.sleep(delayMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
      synchronized (monitor) {
        pairs.add(pair);
        monitor.notifyAll();
      }
    }

    @Override
    public void close() {}

    List<MessagePair> snapshot() {
      synchronized (monitor) {
        return new ArrayList<>(pairs);
      }
    }

    boolean awaitPairs(int expected, long timeout, TimeUnit unit) throws InterruptedException {
      if (expected <= 0) {
        return true;
      }
      long deadline = System.nanoTime() + unit.toNanos(timeout);
      synchronized (monitor) {
        while (pairs.size() < expected) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            return false;
          }
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        }
        return true;
      }
    }

    Set<String> workerThreads() {
      return workerThreads;
    }
  }
}
