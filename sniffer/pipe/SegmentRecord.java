package sniffer.pipe;

/** One captured TCP segment (payload may be empty). */
public final class SegmentRecord {
  public long tsMicros;      // capture timestamp (us)
  public String src;         // IPv4/IPv6 string
  public int sport;
  public String dst;
  public int dport;
  public long seq;           // TCP sequence number
  public int flags;          // FIN=1, SYN=2, RST=4, PSH=8, ACK=16 (bitmask)
  public byte[] payload;     // may be empty
  public int len;            // bytes valid in payload (>=0)

  public SegmentRecord() {}

  public SegmentRecord fill(long tsMicros, String src, int sport, String dst, int dport,
                            long seq, int flags, byte[] payload, int len) {
    this.tsMicros = tsMicros;
    this.src = src; this.sport = sport; this.dst = dst; this.dport = dport;
    this.seq = seq; this.flags = flags;

    // Normalize inputs
    if (len < 0) len = 0;
    if (payload == null) {
      // Defensive: never crash; treat as empty
      this.payload = new byte[0];
      this.len = 0;
      return this;
    }
    if (len > payload.length) len = payload.length;

    if (this.payload == null || this.payload.length < len) {
      this.payload = new byte[len];
    }
    if (len > 0) {
      System.arraycopy(payload, 0, this.payload, 0, len);
    }
    this.len = len;
    return this;
  }

  // Flags for convenience
  public static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;
}


