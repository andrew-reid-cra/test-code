package ca.gc.cra.radar.domain.capture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SegmentRecordTest {

  @Test
  void constructorDefensivelyCopiesPayload() {
    byte[] original = {1, 2, 3};
    SegmentRecord record = new SegmentRecord(1L, "1.1.1.1", 1234, "2.2.2.2", 80, 10L, SegmentRecord.ACK, original);

    original[0] = 9;

    byte[] stored = record.payload();
    assertArrayEquals(new byte[] {1, 2, 3}, stored);
    assertNotSame(original, stored);
  }

  @Test
  void nullPayloadReplacedWithEmptyArray() {
    SegmentRecord record = new SegmentRecord(0L, "1.1.1.1", 1, "2.2.2.2", 2, 0L, 0, null);

    assertNotNull(record.payload());
    assertEquals(0, record.payload().length);
  }
}
