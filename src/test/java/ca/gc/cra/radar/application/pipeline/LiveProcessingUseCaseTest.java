package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.detect.DefaultProtocolDetector;
import ca.gc.cra.radar.infrastructure.protocol.http.HttpMessageReconstructor;
import ca.gc.cra.radar.infrastructure.protocol.http.HttpPairingEngineAdapter;
import ca.gc.cra.radar.infrastructure.protocol.http.HttpProtocolModule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
    ProtocolDetector detector =
        new DefaultProtocolDetector(List.of(new HttpProtocolModule()), enabledProtocols);

    Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories =
        Map.of(ProtocolId.HTTP, () -> new HttpMessageReconstructor(ClockPort.SYSTEM, MetricsPort.NO_OP));
    Map<ProtocolId, Supplier<PairingEngine>> pairingFactories =
        Map.of(ProtocolId.HTTP, HttpPairingEngineAdapter::new);

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
            enabledProtocols);

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

      MessagePair pair = persistence.pairs().get(0);
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
    } finally {
      worker.interrupt();
      worker.join(2000);
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
      return Optional.of(new ByteStream(segment.flow(), segment.fromClient(), segment.payload(), segment.timestampMicros()));
    }
  }

  private static final class RecordingPersistence implements PersistencePort {
    private final List<MessagePair> pairs = new ArrayList<>();
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void persist(MessagePair pair) {
      pairs.add(pair);
      latch.countDown();
    }

    List<MessagePair> pairs() {
      return pairs;
    }

    boolean awaitPairs(int expected, long timeout, TimeUnit unit) throws InterruptedException {
      if (expected <= 0) {
        return true;
      }
      return latch.await(timeout, unit);
    }
  }
}












