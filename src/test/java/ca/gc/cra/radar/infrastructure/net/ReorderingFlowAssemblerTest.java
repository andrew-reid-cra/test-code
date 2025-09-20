package ca.gc.cra.radar.infrastructure.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReorderingFlowAssemblerTest {
  private static final FiveTuple FLOW = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");

  @Test
  void assemblesOutOfOrderSegments() {
    ReorderingFlowAssembler assembler = new ReorderingFlowAssembler(MetricsPort.NO_OP, "test");

    TcpSegment first = segment(1_000, true, "foo");
    Optional<ByteStream> firstOut = assembler.accept(first);
    assertTrue(firstOut.isPresent(), "expected first contiguous payload");
    assertArrayEquals(bytes("foo"), firstOut.get().data());

    TcpSegment third = segment(1_006, true, "bar");
    assertFalse(assembler.accept(third).isPresent(), "gap should delay emission");

    TcpSegment second = segment(1_003, true, "baz");
    Optional<ByteStream> merged = assembler.accept(second);
    assertTrue(merged.isPresent(), "gap filled should emit merged payload");
    assertArrayEquals(bytes("bazbar"), merged.get().data());
  }

  @Test
  void dropsRetransmittedOverlap() {
    ReorderingFlowAssembler assembler = new ReorderingFlowAssembler(MetricsPort.NO_OP, "test");

    Optional<ByteStream> hello = assembler.accept(segment(2_000, true, "hello"));
    assertTrue(hello.isPresent());
    assertArrayEquals(bytes("hello"), hello.get().data());

    Optional<ByteStream> continuation = assembler.accept(segment(2_003, true, "lo world"));
    assertTrue(continuation.isPresent());
    assertArrayEquals(bytes(" world"), continuation.get().data());

    Optional<ByteStream> retransmit = assembler.accept(segment(2_003, true, "lo world"));
    assertFalse(retransmit.isPresent(), "duplicate payload should be ignored");
  }

  private static TcpSegment segment(long seq, boolean fromClient, String data) {
    byte[] payload = bytes(data);
    return new TcpSegment(
        FLOW,
        seq,
        fromClient,
        payload,
        false,
        false,
        false,
        false,
        true,
        seq);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
