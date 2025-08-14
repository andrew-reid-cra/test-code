package sniffer.tools;

import sniffer.pipe.SegmentIO;
import sniffer.pipe.SegmentRecord;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class SegbinGrep {
  private static final DateTimeFormatter TS = DateTimeFormatter
      .ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault());

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(arg(args, "in", "./cap-out"));
    String needle = arg(args, "needle", null);
    int ctx = Integer.parseInt(arg(args, "ctx", "32"));
    if (needle == null || needle.isEmpty()) {
      System.err.println("Usage: sniffer.tools.SegbinGrep in=/path/to/cap-out needle=STRING [ctx=32]");
      System.exit(2);
    }
    byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
    int total = 0, hits = 0;

    try (SegmentIO.Reader r = new SegmentIO.Reader(dir)) {
      for (;;) {
        SegmentRecord s = r.next();
        if (s == null) break;
        total++;
        byte[] p = s.payload != null ? s.payload : new byte[0];
        int off = indexOf(p, n);
        if (off >= 0) {
          hits++;
          int a = Math.max(0, off - ctx), b = Math.min(p.length, off + n.length + ctx);
          String left = toAscii(p, a, off);
          String mid  = toAscii(p, off, off + n.length);
          String right= toAscii(p, off + n.length, b);
          System.out.printf("%s GREP %s:%d -> %s:%d seq=%d len=%d off=%d%n",
              TS.format(Instant.ofEpochMilli(s.tsMicros/1000)),
              s.src, s.sport, s.dst, s.dport, s.seq, s.len, off);
          System.out.printf("        \"%s[%s]%s\"%n", left, mid, right);
        }
      }
    }
    System.out.printf("Scanned %,d segments, matches: %,d%n", total, hits);
  }

  private static String arg(String[] args, String k, String def){
    for (String a: args){ int i=a.indexOf('='); if (i>0 && a.substring(0,i).equals(k)) return a.substring(i+1); }
    return def;
  }
  private static int indexOf(byte[] a, byte[] n){
    outer: for (int i=0;i + n.length <= a.length;i++){
      for (int j=0;j<n.length;j++) if (a[i+j] != n[j]) continue outer;
      return i;
    }
    return -1;
  }
  private static String toAscii(byte[] a, int s, int e){
    StringBuilder sb = new StringBuilder(e - s);
    for (int i=s;i<e;i++){
      int c = a[i] & 0xff;
      if (c >= 32 && c < 127) sb.append((char)c);
      else sb.append('.');
    }
    return sb.toString();
  }
}

