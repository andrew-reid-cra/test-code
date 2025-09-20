package sniffer.capture;

import sniffer.adapters.libpcap.JnrPcapAdapter;
import ca.gc.cra.radar.infrastructure.protocol.http.legacy.SegmentSink;
import ca.gc.cra.radar.infrastructure.protocol.http.legacy.tn3270.Tn3270Assembler;
import sniffer.pipe.SegmentIO;
import sniffer.pipe.SegmentRecord;
import sniffer.spi.Pcap;
import sniffer.spi.PcapException;
import sniffer.spi.PcapHandle;

import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public final class CaptureMain {

  private static final DateTimeFormatter TS = DateTimeFormatter
      .ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault());

  public static void main(String[] args) throws Exception {
    CaptureConfig cfg = CaptureConfig.fromArgs(args);
    log("Config iface=%s buf=%dMB snap=%d timeout=%dms bpf='%s' out=%s rollMiB=%d",
        cfg.iface, cfg.bufBytes/1024/1024, cfg.snap, cfg.timeoutMs, cfg.bpf, cfg.outDir, cfg.rollMiB);
    Files.createDirectories(cfg.outDir);

    SegmentSink tnSink = null;
    Tn3270Assembler tnAsm = null;
    DirTracker dirTracker = null;
    if (cfg.tnOutDir != null) {
      Files.createDirectories(cfg.tnOutDir);
      tnSink = new SegmentSink(SegmentSink.Config.hourlyGiB(cfg.tnOutDir));
      tnAsm = new Tn3270Assembler(tnSink);
      dirTracker = new DirTracker();
      log("TN3270 snapshots enabled -> %s", cfg.tnOutDir);
    }

    try (SegmentIO.Writer writer = new SegmentIO.Writer(cfg.outDir, cfg.fileBase, cfg.rollMiB);
         PcapHandle ph = open(cfg)) {

      boolean running = true;
      final SegmentSink tnSinkFinal = tnSink;
      final Tn3270Assembler tnAsmFinal = tnAsm;
      final DirTracker tracker = dirTracker;

      while (running) {
        running = ph.next((tsMicros, data, caplen) -> {
          var d = TcpDecoder.decode(data, caplen);
          if (d == null) return; // not TCP or undecodable

          // Drop pure ACKs (no payload, only ACK flag) to shrink file size
          boolean pureAck = d.payloadLen <= 0 && (d.flags & ~SegmentRecord.ACK) == 0;
          if (pureAck) return;

          byte[] bytes = slice(data, d.payloadOff, d.payloadLen); // always non-null & clamped
          SegmentRecord seg = new SegmentRecord()
              .fill(tsMicros, d.src, d.sport, d.dst, d.dport, d.seq, d.flags, bytes, bytes.length);

          try {
            writer.append(seg);
          } catch (Exception ioe) {
            System.err.println("Write failed: " + ioe);
          }

          if (tnAsmFinal != null && bytes.length > 0) {
            boolean syn = (d.flags & SegmentRecord.SYN) != 0;
            boolean ack = (d.flags & SegmentRecord.ACK) != 0;
            boolean fin = (d.flags & SegmentRecord.FIN) != 0;
            boolean fromClient = tracker.fromClient(d.src, d.sport, d.dst, d.dport, syn, ack);
            tnAsmFinal.onTcpSegment(tsMicros,
                d.src, d.sport, d.dst, d.dport,
                d.seq, fromClient, bytes, 0, bytes.length, fin);
          }
        });
      }
      writer.flush();
    } finally {
      if (tnSink != null) {
        try { tnSink.close(); } catch (Exception ignore) { }
      }
    }
  }

  private static PcapHandle open(CaptureConfig c) throws PcapException {
    Pcap p = new JnrPcapAdapter();
    PcapHandle h = p.openLive(c.iface, c.snap, c.promisc, c.timeoutMs, c.bufBytes, c.immediate);
    if (c.bpf != null && !c.bpf.isEmpty()) h.setFilter(c.bpf);
    return h;
  }

  /** Safe slice: clamps off/len to the array bounds and never returns null. */
  private static byte[] slice(byte[] a, int off, int len){
    if (a == null) return new byte[0];
    if (off < 0) off = 0;
    if (off > a.length) off = a.length;
    if (len < 0) len = 0;
    int max = Math.max(0, a.length - off);
    int n = Math.min(len, max);
    if (n == 0) return new byte[0];
    byte[] b = new byte[n];
    System.arraycopy(a, off, b, 0, n);
    return b;
  }

  private static void log(String fmt, Object... args){
    System.out.println(TS.format(Instant.now()) + " CAP " + String.format(fmt, args));
  }

  private static final class DirTracker {
    private static final class End { final String ip; final int port; End(String ip, int port){ this.ip=ip; this.port=port; } }
    private final Map<String, End> clients = new HashMap<>();

    boolean fromClient(String src, int sport, String dst, int dport, boolean syn, boolean ack) {
      String key = key(src, sport, dst, dport);
      End c = clients.get(key);
      if (syn && !ack) {
        clients.put(key, new End(src, sport));
        return true;
      }
      if (c == null) {
        clients.put(key, new End(src, sport));
        return true;
      }
      return src.equals(c.ip) && sport == c.port;
    }

    private static String key(String a, int ap, String b, int bp) {
      int cmp = a.equals(b) ? Integer.compare(ap, bp) : a.compareTo(b);
      return (cmp <= 0) ? (a+":"+ap+"|"+b+":"+bp) : (b+":"+bp+"|"+a+":"+ap);
    }
  }

  private CaptureMain() {}
}


