package ca.gc.cra.radar.domain.capture;

public record SegmentRecord(
    long timestampMicros,
    String srcIp,
    int srcPort,
    String dstIp,
    int dstPort,
    long sequence,
    int flags,
    byte[] payload) {
  public SegmentRecord {
    payload = payload != null ? payload.clone() : new byte[0];
  }

  public static final int FIN = 1;
  public static final int SYN = 2;
  public static final int RST = 4;
  public static final int PSH = 8;
  public static final int ACK = 16;
}


