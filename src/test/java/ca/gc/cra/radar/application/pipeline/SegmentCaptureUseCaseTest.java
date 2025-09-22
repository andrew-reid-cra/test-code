package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SegmentCaptureUseCaseTest {
  @Test
  void persistsSegmentsFromPacketSource() throws Exception {
    FiveTuple flow = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
    TcpSegment segment =
        new TcpSegment(flow, 42L, true, new byte[] {1, 2, 3}, false, false, false, false, true, 5L);

    StubPacketSource packetSource =
        new StubPacketSource(List.of(new RawFrame(new byte[] {0}, 5L)));
    StubFrameDecoder frameDecoder = new StubFrameDecoder(List.of(segment));
    RecordingPersistence persistence = new RecordingPersistence();

    SegmentCaptureUseCase useCase =
        new SegmentCaptureUseCase(packetSource, frameDecoder, persistence, MetricsPort.NO_OP);

    Thread worker =
        new Thread(
            () -> {
              try {
                useCase.run();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            "segment-capture-test");
    worker.setDaemon(true);

    try {
      worker.start();
      assertTrue(persistence.awaitRecords(1, 2, TimeUnit.SECONDS));
      SegmentRecord record = persistence.records().get(0);
      assertEquals(5L, record.timestampMicros());
      assertEquals("10.0.0.1", record.srcIp());
      assertEquals(1234, record.srcPort());
      assertEquals("10.0.0.2", record.dstIp());
      assertEquals(80, record.dstPort());
      assertEquals(42L, record.sequence());
      assertEquals(3, record.payload().length);
      assertEquals(SegmentRecord.ACK, record.flags());
    } finally {
      worker.interrupt();
      worker.join(2000);
    }
  }

  @Test
  void stopsWhenPacketSourceExhausted() throws Exception {
    FiveTuple flow = new FiveTuple("192.0.2.1", 4321, "198.51.100.2", 80, "TCP");
    TcpSegment segment =
        new TcpSegment(flow, 7L, true, new byte[] {9}, false, false, false, false, true, 3L);

    ExhaustingPacketSource packetSource =
        new ExhaustingPacketSource(List.of(new RawFrame(new byte[] {1}, 10L)));
    StubFrameDecoder frameDecoder = new StubFrameDecoder(List.of(segment));
    RecordingPersistence persistence = new RecordingPersistence();

    SegmentCaptureUseCase useCase =
        new SegmentCaptureUseCase(packetSource, frameDecoder, persistence, MetricsPort.NO_OP);

    useCase.run();

    assertEquals(1, persistence.records().size());
    assertTrue(packetSource.isExhausted());
  }

  private static final class StubPacketSource implements PacketSource {
    private final Queue<RawFrame> frames;

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
      Thread.sleep(10);
      return Optional.empty();
    }

    @Override
    public void close() {}
  }

  private static final class ExhaustingPacketSource implements PacketSource {
    private final Queue<RawFrame> frames;
    private boolean exhausted;

    ExhaustingPacketSource(List<RawFrame> frames) {
      this.frames = new ArrayDeque<>(frames);
    }

    @Override
    public void start() {}

    @Override
    public Optional<RawFrame> poll() {
      RawFrame next = frames.poll();
      if (next != null) {
        return Optional.of(next);
      }
      exhausted = true;
      return Optional.empty();
    }

    @Override
    public boolean isExhausted() {
      return exhausted;
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

  private static final class RecordingPersistence implements SegmentPersistencePort {
    private final List<SegmentRecord> records = new ArrayList<>();
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void persist(SegmentRecord record) {
      records.add(record);
      latch.countDown();
    }

    @Override
    public void close() {}

    List<SegmentRecord> records() {
      return records;
    }

    boolean awaitRecords(int expected, long timeout, TimeUnit unit) throws InterruptedException {
      if (expected <= 0) {
        return true;
      }
      return latch.await(timeout, unit);
    }
  }
}


