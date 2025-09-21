package ca.gc.cra.radar.domain.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TcpSegmentTest {

  private static final FiveTuple FLOW = new FiveTuple("10.0.0.1", 1000, "10.0.0.2", 80, "TCP");

  @Test
  void constructorDefensivelyCopiesPayload() {
    byte[] original = {1, 2, 3, 4};
    TcpSegment segment = new TcpSegment(FLOW, 5L, true, original, false, false, false, false, true, 11L);

    original[2] = 9;

    byte[] stored = segment.payload();
    assertArrayEquals(new byte[] {1, 2, 3, 4}, stored);
    assertNotSame(original, stored);
  }

  @Test
  void nullPayloadReplacedWithEmptyArray() {
    TcpSegment segment = new TcpSegment(FLOW, 1L, false, null, false, false, false, false, false, 0L);
    assertNotNull(segment.payload());
    assertEquals(0, segment.payload().length);
  }
}
