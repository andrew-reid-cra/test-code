package ca.gc.cra.radar.infrastructure.persistence.legacy;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import org.junit.jupiter.api.Test;

class SegmentRecordMapperTest {
  @Test
  void mapsLegacyRecord() {
    sniffer.pipe.SegmentRecord legacy = new sniffer.pipe.SegmentRecord();
    legacy.fill(1L, "1.1.1.1", 1234, "2.2.2.2", 80, 42L, sniffer.pipe.SegmentRecord.ACK, new byte[] {1, 2}, 2);
    SegmentRecord record = SegmentRecordMapper.fromLegacy(legacy);
    assertEquals("1.1.1.1", record.srcIp());
    assertEquals(80, record.dstPort());
    assertEquals(42L, record.sequence());
    assertArrayEquals(new byte[] {1, 2}, record.payload());
  }

  @Test
  void convertsToLegacy() {
    SegmentRecord record =
        new SegmentRecord(5L, "3.3.3.3", 1111, "4.4.4.4", 2222, 100L, SegmentRecord.ACK, new byte[] {9});
    sniffer.pipe.SegmentRecord legacy = SegmentRecordMapper.toLegacy(record);
    assertEquals(5L, legacy.tsMicros);
    assertEquals("3.3.3.3", legacy.src);
    assertEquals(2222, legacy.dport);
    assertEquals(100L, legacy.seq);
    assertEquals(1, legacy.len);
    assertEquals(9, legacy.payload[0]);
  }
}


