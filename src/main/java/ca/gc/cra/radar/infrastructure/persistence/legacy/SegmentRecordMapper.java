package ca.gc.cra.radar.infrastructure.persistence.legacy;

import ca.gc.cra.radar.domain.capture.SegmentRecord;

public final class SegmentRecordMapper {
  private SegmentRecordMapper() {}

  public static SegmentRecord fromLegacy(sniffer.pipe.SegmentRecord legacy) {
    return new SegmentRecord(
        legacy.tsMicros,
        legacy.src,
        legacy.sport,
        legacy.dst,
        legacy.dport,
        legacy.seq,
        legacy.flags,
        legacy.payload == null ? new byte[0] : legacy.payload.clone());
  }

  public static sniffer.pipe.SegmentRecord toLegacy(SegmentRecord record) {
    sniffer.pipe.SegmentRecord legacy = new sniffer.pipe.SegmentRecord();
    byte[] payload = record.payload() == null ? new byte[0] : record.payload();
    legacy.fill(
        record.timestampMicros(),
        record.srcIp(),
        record.srcPort(),
        record.dstIp(),
        record.dstPort(),
        record.sequence(),
        record.flags(),
        payload,
        payload.length);
    return legacy;
  }
}


