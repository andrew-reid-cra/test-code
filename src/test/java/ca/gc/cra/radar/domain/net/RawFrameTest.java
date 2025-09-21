package ca.gc.cra.radar.domain.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RawFrameTest {

  @Test
  void constructorDefensivelyCopiesData() {
    byte[] original = {5, 6, 7};
    RawFrame frame = new RawFrame(original, 42L);

    original[1] = 0;

    byte[] stored = frame.data();
    assertArrayEquals(new byte[] {5, 6, 7}, stored);
    assertNotSame(original, stored);
  }

  @Test
  void nullDataReplacedWithEmptyArray() {
    RawFrame frame = new RawFrame(null, 1L);
    assertNotNull(frame.data());
    assertEquals(0, frame.data().length);
  }
}
