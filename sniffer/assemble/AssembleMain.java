package sniffer.assemble;

import sniffer.domain.HttpAssembler;
import sniffer.domain.HttpStreamSink;
import sniffer.domain.SegmentSink;
import sniffer.domain.SessionIdExtractor;
import sniffer.pipe.SegmentIO;
import sniffer.pipe.SegmentRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JVM #2: read *.segbin files and feed HttpAssembler. */
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
    Path inDir  = pathArg(args, "in",  "./cap-out");
    Path outDir = pathArg(args, "out", "./http-out");
    Files.createDirectories(outDir);

    log("Reading from %s, writing HTTP segments under %s", inDir, outDir);

    HttpStreamSink sink = new SegmentSink(SegmentSink.Config.hourlyGiB(outDir));

    // SessionIdExtractor is a final class with (Collection<String>, Collection<String>) ctor.
    // Pass empty lists for now (no-op extraction). You can wire real keys later.
    SessionIdExtractor sx = new SessionIdExtractor(
        java.util.Collections.emptyList(),
        java.util.Collections.emptyList()
    );

    HttpAssembler asm = new HttpAssembler(sink, sx);
    DirBook dirs = new DirBook();

    int total=0;
    try (SegmentIO.Reader reader = new SegmentIO.Reader(inDir)) {
      for (;;) {
        SegmentRecord r = reader.next();
        if (r == null) break;
        boolean fromClient = dirs.fromClient(r);
        asm.onTcpSegment(
            r.tsMicros, r.src, r.sport, r.dst, r.dport,
            r.seq, fromClient, r.payload!=null?r.payload:new byte[0], 0, r.len,
            (r.flags & SegmentRecord.FIN) != 0
        );
        total++;
        if ((total & 0xFFFF) == 0) log("Processed %,d segments", total);
      }
    }
    log("Done. Total segments processed: %,d", total);
  }

  private static Path pathArg(String[] args, String key, String def){
    for (String a: args){ int i=a.indexOf('='); if (i>0 && a.substring(0,i).equals(key)) return Path.of(a.substring(i+1)); }
    return Path.of(def);
  }

  private static void log(String fmt, Object... args){
    System.out.println(TS.format(Instant.now()) + " ASM " + String.format(fmt, args));
  }

  private AssembleMain() {}
}
