package ca.gc.cra.radar.infrastructure.net;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NoOpFlowAssemblerTest {
  private static final FiveTuple FLOW = new FiveTuple("10.0.0.1", 4444, "10.0.0.2", 80, "TCP");

  @Test
  void returnsEmptyForNullSegments() {
    NoOpFlowAssembler assembler = new NoOpFlowAssembler();
    assertTrue(assembler.accept(null).isEmpty());
  }

  @Test
  void returnsEmptyForAnySegment() {
    NoOpFlowAssembler assembler = new NoOpFlowAssembler();
    TcpSegment segment = new TcpSegment(
        FLOW,
        1L,
        true,
        "data".getBytes(StandardCharsets.US_ASCII),
        false,
        false,
        false,
        false,
        true,
        1L);

    assertTrue(assembler.accept(segment).isEmpty());
    assertTrue(assembler.accept(segment).isEmpty());
  }
}
