package ca.gc.cra.radar.domain.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ByteStreamTest {

  private static final FiveTuple FLOW = new FiveTuple("1.1.1.1", 1111, "2.2.2.2", 2222, "TCP");

  @Test
  void constructorDefensivelyCopiesData() {
    byte[] original = {10, 20};
    ByteStream stream = new ByteStream(FLOW, true, original, 123L);

    original[0] = 99;

    byte[] stored = stream.data();
    assertArrayEquals(new byte[] {10, 20}, stored);
    assertNotSame(original, stored);
  }

  @Test
  void nullDataReplacedWithEmptyArray() {
    ByteStream stream = new ByteStream(FLOW, false, null, 0L);
    assertNotNull(stream.data());
    assertEquals(0, stream.data().length);
  }
}
