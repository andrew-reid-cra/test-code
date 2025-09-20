package sniffer.assemble;

import ca.gc.cra.radar.infrastructure.protocol.http.legacy.HttpAssembler;
import ca.gc.cra.radar.infrastructure.protocol.http.legacy.HttpStreamSink;
import ca.gc.cra.radar.infrastructure.protocol.http.legacy.SegmentSink;
import ca.gc.cra.radar.infrastructure.protocol.http.legacy.SessionIdExtractor;
import ca.gc.cra.radar.infrastructure.protocol.http.legacy.tn3270.Tn3270Assembler;
import sniffer.pipe.SegmentIO;
import sniffer.pipe.SegmentRecord;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JVM #2: read *.segbin files and feed assemblers. */
public final class AssembleMain {

  private static final DateTimeFormatter TS = DateTimeFormatter
      .ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault());

  // Heuristic: decide "fromClient" per 4-tuple using first-seen (SYN helps if present).
  private static final class DirBook {
    private static final class End { final String ip; final int port; End(String ip, int port){ this.ip=ip; this.port=port; } }
    private final Map<String, End> clientByPair = new ConcurrentHashMap<>();
    private static String pairKey(String a, int ap, String b, int bp){
      int cmp = (a.equals(b) ? Integer.compare(ap, bp) : a.compareTo(b));
      return (cmp <= 0) ? (a+":"+ap+"|"+b+":"+bp) : (b+":"+bp+"|"+a+":"+ap);
    }
    boolean fromClient(SegmentRecord r){
      String key = pairKey(r.src, r.sport, r.dst, r.dport);
      End c = clientByPair.get(key);
      if (c == null) {
        clientByPair.put(key, new End(r.src, r.sport)); // first seen = client
        return true;
      }
      return r.src.equals(c.ip) && r.sport == c.port;
    }
  }

  public static void main(String[] args) throws Exception {
    Map<String,String> cli = parseArgs(args);

    Path inDir  = Paths.get(cli.getOrDefault("in", "./cap-out"));

    boolean httpEnabled = parseBool(cli.getOrDefault("httpEnabled", "true"));
    boolean tnEnabled = parseBool(cli.getOrDefault("tnEnabled", "true"));

    Path httpOut = null;
    if (httpEnabled) {
      String httpPath = cli.containsKey("httpOut") ? cli.get("httpOut") : cli.getOrDefault("out", "./http-out");
      if (httpPath != null && !httpPath.isBlank()) {
        httpOut = Paths.get(httpPath);
      } else {
        httpEnabled = false;
      }
    }

    Path tnOut = null;
    if (tnEnabled) {
      String tnPath = cli.getOrDefault("tnOut", "./tn3270-out");
      if (tnPath != null && !tnPath.isBlank()) {
        tnOut = Paths.get(tnPath);
      } else {
        tnEnabled = false;
      }
    }

    log("Config in=%s httpEnabled=%s httpOut=%s tnEnabled=%s tnOut=%s",
        inDir, httpEnabled, httpOut, tnEnabled, tnOut);

    SegmentSink httpSink = null;
    SegmentSink tnSink = null;
    try (SegmentIO.Reader reader = new SegmentIO.Reader(inDir)) {
      if (httpEnabled && httpOut != null) {
        httpSink = new SegmentSink(SegmentSink.Config.hourlyGiB(httpOut));
      }
      if (tnEnabled && tnOut != null) {
        tnSink = new SegmentSink(SegmentSink.Config.hourlyGiB(tnOut));
      }

      SessionIdExtractor sx = (httpSink != null)
          ? new SessionIdExtractor(java.util.Collections.emptyList(), java.util.Collections.emptyList())
          : null;

      HttpAssembler httpAsm = (httpSink != null) ? new HttpAssembler((HttpStreamSink) httpSink, sx) : null;
      Tn3270Assembler tn3270Asm = (tnSink != null) ? new Tn3270Assembler(tnSink) : null;
      DirBook dirs = new DirBook();

      int total = 0;
      for (;;) {
        SegmentRecord r = reader.next();
        if (r == null) break;
        boolean fromClient = dirs.fromClient(r);
        byte[] payload = (r.payload != null) ? r.payload : new byte[0];
        int len = r.len;
        boolean fin = (r.flags & SegmentRecord.FIN) != 0;

        if (httpAsm != null) {
          httpAsm.onTcpSegment(
              r.tsMicros, r.src, r.sport, r.dst, r.dport,
              r.seq, fromClient, payload, 0, len, fin
          );
        }

        if (tn3270Asm != null) {
          tn3270Asm.onTcpSegment(
              r.tsMicros, r.src, r.sport, r.dst, r.dport,
              r.seq, fromClient, payload, 0, len, fin
          );
        }

        total++;
        if ((total & 0xFFFF) == 0) log("Processed %,d segments", total);
      }

      log("Done. Total segments processed: %,d", total);
    } finally {
      if (httpSink != null) {
        try { httpSink.close(); } catch (Exception ignore) { }
      }
      if (tnSink != null) {
        try { tnSink.close(); } catch (Exception ignore) { }
      }
    }
  }

  private static Map<String,String> parseArgs(String[] args) {
    Map<String,String> m = new HashMap<>();
    for (String a : args) {
      int idx = a.indexOf('=');
      if (idx > 0) {
        m.put(a.substring(0, idx), a.substring(idx + 1));
      }
    }
    return m;
  }

  private static boolean parseBool(String v) {
    return v != null && Boolean.parseBoolean(v);
  }

  private static void log(String fmt, Object... args){
    System.out.println(TS.format(Instant.now()) + " ASM " + String.format(fmt, args));
  }

  private AssembleMain() {}
}



