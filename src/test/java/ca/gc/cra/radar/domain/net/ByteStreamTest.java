package ca.gc.cra.radar.domain.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ByteStreamTest {

  private static final FiveTuple FLOW = new FiveTuple("1.1.1.1", 1111, "2.2.2.2", 2222, "TCP");

  @Test
  void constructorRetainsProvidedArrayReference() {
    byte[] original = {10, 20};
    ByteStream stream = new ByteStream(FLOW, true, original, 123L);

    assertSame(original, stream.data());
  }

  @Test
  void nullDataReplacedWithEmptyArray() {
    ByteStream stream = new ByteStream(FLOW, false, null, 0L);
    assertNotNull(stream.data());
    assertEquals(0, stream.data().length);
  }
}

