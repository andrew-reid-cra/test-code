package sniffer.domain.tn3270;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming Telnet record decoder specialised for TN3270 traffic.
 * It consumes TCP payload bytes, strips Telnet command negotiations,
 * and emits byte arrays representing 3270 data records (delimited by IAC EOR).
 */
final class TelnetRecordDecoder {
  private static final int IAC = 0xFF;
  private static final int SB  = 0xFA;
  private static final int SE  = 0xF0;
  private static final int EOR = 0xEF;
  private static final int WILL = 0xFB;
  private static final int WONT = 0xFC;
  private static final int DO   = 0xFD;
  private static final int DONT = 0xFE;

  private final ByteRing record = new ByteRing(1024);

  private boolean pendingIac = false;
  private boolean inSubneg = false;
  private int awaitingOptionCmd = 0;

  TelnetRecordDecoder() {}

  /**
   * Feed ordered TCP payload bytes and collect completed TN3270 records.
   */
  List<byte[]> feed(byte[] data, int off, int len) {
    List<byte[]> out = null;
    for (int i = 0; i < len; i++) {
      int b = data[off + i] & 0xFF;

      if (awaitingOptionCmd != 0) {
        awaitingOptionCmd = 0; // consume Telnet option byte
        continue;
      }

      if (!pendingIac) {
        if (b == IAC) {
          pendingIac = true;
          continue;
        }
        if (!inSubneg) {
          record.write((byte) b);
        }
        continue;
      }

      // We previously saw IAC
      pendingIac = false;
      switch (b) {
        case IAC -> record.write((byte) IAC); // escaped 0xFF
        case SB -> inSubneg = true;
        case SE -> inSubneg = false;
        case WILL, WONT, DO, DONT -> awaitingOptionCmd = b;
        case EOR -> {
          if (!record.isEmpty()) {
            if (out == null) out = new ArrayList<>();
            out.add(record.take());
          }
        }
        default -> { /* ignore other commands (NOP, GA, etc) */ }
      }
    }
    return out == null ? List.of() : out;
  }

  private static final class ByteRing {
    private byte[] buf;
    private int size;

    ByteRing(int cap) { this.buf = new byte[Math.max(256, cap)]; this.size = 0; }

    void write(byte b) {
      ensureCapacity(1);
      buf[size++] = b;
    }

    boolean isEmpty() { return size == 0; }

    byte[] take() {
      byte[] out = new byte[size];
      System.arraycopy(buf, 0, out, 0, size);
      size = 0;
      return out;
    }

    private void ensureCapacity(int extra) {
      int needed = size + extra;
      if (needed <= buf.length) return;
      int n = buf.length;
      while (n < needed) n <<= 1;
      byte[] nb = new byte[Math.min(n, 1 << 20)];
      System.arraycopy(buf, 0, nb, 0, size);
      buf = nb;
    }
  }
}
