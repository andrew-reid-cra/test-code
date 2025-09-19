package sniffer.domain.tn3270;

import sniffer.domain.SegmentSink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight TCP stream assembler for TN3270 traffic. It reassembles in-order
 * payloads, extracts Telnet records, and hands complete TN3270 screens to the sink.
 */
public final class Tn3270Assembler {
  private static final int MAX_CONN = 4096;
  private static final int MAX_OOO_PER_DIR = 256 * 1024;

  private final SegmentSink sink;
  private final ConcurrentHashMap<FlowKey, Conn> flows = new ConcurrentHashMap<>();

  public Tn3270Assembler(SegmentSink sink) {
    this.sink = sink;
  }

  public void onTcpSegment(long tsMicros, String src, int sport, String dst, int dport,
                           long seq, boolean fromClient, byte[] data, int off, int len, boolean fin) {
    if (len <= 0) {
      markFin(src, sport, dst, dport, fromClient, fin);
      return;
    }
    FlowKey key = FlowKey.of(src, sport, dst, dport);
    Conn conn = flows.computeIfAbsent(key, k -> {
      if (flows.size() >= MAX_CONN) return null;
      return new Conn();
    });
    if (conn == null) return;

    Dir dir = fromClient ? conn.client : conn.server;
    if (!dir.accepting && !maybeTelnet(data, off, len)) {
      // Fast path: ignore obvious non-Telnet payloads until proven otherwise.
      return;
    }

    byte[] payload = Arrays.copyOfRange(data, off, off + len);
    dir.feed(tsMicros, seq, payload, fin, fromClient,
        src, sport, dst, dport, conn.session);

    if (fin) {
      if (fromClient) conn.clientFin = true; else conn.serverFin = true;
      if (conn.clientFin && conn.serverFin) flows.remove(key);
    }
  }

  private void markFin(String src, int sport, String dst, int dport, boolean fromClient, boolean fin) {
    if (!fin) return;
    FlowKey key = FlowKey.of(src, sport, dst, dport);
    Conn conn = flows.get(key);
    if (conn == null) return;
    if (fromClient) conn.clientFin = true; else conn.serverFin = true;
    if (conn.clientFin && conn.serverFin) flows.remove(key);
  }

  private static boolean maybeTelnet(byte[] data, int off, int len) {
    for (int i = 0; i < len; i++) {
      if ((data[off + i] & 0xFF) == 0xFF) return true;
    }
    return false;
  }

  private final class Conn {
    final Dir client = new Dir(true);
    final Dir server = new Dir(false);
    final Tn3270Session session = new Tn3270Session(sink);
    boolean clientFin;
    boolean serverFin;
  }

  private final class Dir {
    final boolean fromClient;
    final TelnetRecordDecoder decoder = new TelnetRecordDecoder();
    long nextSeq = -1;
    final Map<Long, byte[]> ooo = new ConcurrentHashMap<>();
    int oooBytes = 0;
    boolean accepting = false;

    Dir(boolean fromClient) { this.fromClient = fromClient; }

    void feed(long tsMicros, long seq, byte[] payload, boolean fin, boolean fromClient,
              String src, int sport, String dst, int dport, Tn3270Session session) {
      if (nextSeq == -1) {
        nextSeq = seq + payload.length;
        accepting = true;
        process(tsMicros, payload, src, sport, dst, dport, session);
        drain(tsMicros, src, sport, dst, dport, session);
        return;
      }
      if (seq == nextSeq) {
        nextSeq += payload.length;
        process(tsMicros, payload, src, sport, dst, dport, session);
        drain(tsMicros, src, sport, dst, dport, session);
      } else if (seq > nextSeq) {
        if (oooBytes + payload.length > MAX_OOO_PER_DIR) return;
        oooBytes += payload.length;
        ooo.put(seq, payload);
      } else {
        long delta = nextSeq - seq;
        if (delta >= payload.length) return;
        int start = (int) delta;
        byte[] trimmed = Arrays.copyOfRange(payload, start, payload.length);
        nextSeq += trimmed.length;
        process(tsMicros, trimmed, src, sport, dst, dport, session);
        drain(tsMicros, src, sport, dst, dport, session);
      }
    }

    private void drain(long tsMicros, String src, int sport, String dst, int dport, Tn3270Session session) {
      boolean progress;
      do {
        progress = false;
        byte[] next = ooo.remove(nextSeq);
        if (next != null) {
          oooBytes -= next.length;
          nextSeq += next.length;
          process(tsMicros, next, src, sport, dst, dport, session);
          progress = true;
        }
      } while (progress);
    }

    private void process(long tsMicros, byte[] payload,
                         String src, int sport, String dst, int dport,
                         Tn3270Session session) {
      List<byte[]> records = decoder.feed(payload, 0, payload.length);
      if (records.isEmpty()) return;
      for (byte[] rec : records) {
        if (fromClient) session.onClientRecord(tsMicros, src, sport, dst, dport, rec);
        else session.onServerRecord(tsMicros, src, sport, dst, dport, rec);
      }
    }
  }

  private record FlowKey(String aIp, int aPort, String bIp, int bPort) {
    static FlowKey of(String src, int sport, String dst, int dport) {
      if (less(src, sport, dst, dport)) return new FlowKey(src, sport, dst, dport);
      return new FlowKey(dst, dport, src, sport);
    }
    private static boolean less(String a, int ap, String b, int bp) {
      int cmp = a.compareTo(b);
      if (cmp != 0) return cmp < 0;
      return ap < bp;
    }
  }
}
