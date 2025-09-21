package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class LiveProcessingUseCaseTest {
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
    worker.setDaemon(true);

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
    worker.setDaemon(true);

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
