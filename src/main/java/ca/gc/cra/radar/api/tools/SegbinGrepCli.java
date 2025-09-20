package ca.gc.cra.radar.api.tools;

import ca.gc.cra.radar.api.CliArgsParser;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.infrastructure.persistence.SegmentIoAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * CLI utility that scans recorded segment files for a byte sequence and prints matches with context.
 */
public final class SegbinGrepCli {
  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault());

  private SegbinGrepCli() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> kv = CliArgsParser.toMap(args);
    String inputDir = kv.getOrDefault("in", "./cap-out");
    String needle = kv.getOrDefault("needle", "");
    int ctx = parseInt(kv.getOrDefault("ctx", "32"));

    if (needle == null || needle.isBlank()) {
      usage();
      return;
    }

    byte[] pattern = needle.getBytes(StandardCharsets.ISO_8859_1);
    int total = 0;
    int hits = 0;

    try (SegmentIoAdapter.Reader reader = new SegmentIoAdapter.Reader(Path.of(inputDir))) {
      SegmentRecord record;
      while ((record = reader.next()) != null) {
        total++;
        byte[] payload = record.payload();
        if (payload.length == 0) {
          continue;
        }
        int offset = indexOf(payload, pattern);
        if (offset >= 0) {
          hits++;
          int start = Math.max(0, offset - ctx);
          int end = Math.min(payload.length, offset + pattern.length + ctx);
          String left = toAscii(payload, start, offset);
          String mid = toAscii(payload, offset, offset + pattern.length);
          String right = toAscii(payload, offset + pattern.length, end);
          System.out.printf(
              "%s GREP %s:%d -> %s:%d seq=%d len=%d off=%d%n",
              TS.format(toInstant(record.timestampMicros())),
              record.srcIp(),
              record.srcPort(),
              record.dstIp(),
              record.dstPort(),
              record.sequence(),
              payload.length,
              offset);
          System.out.printf("        \"%s[%s]%s\"%n", left, mid, right);
        }
      }
    }

    System.out.printf("Scanned %,d segments, matches: %,d%n", total, hits);
  }

  private static void usage() {
    System.err.println(
        "Usage: needle=STRING [in=./cap-out ctx=32]  -- scans segment binaries for a literal byte sequence");
  }

  private static int parseInt(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException ex) {
      return 32;
    }
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    if (needle.length == 0 || haystack.length < needle.length) {
      return -1;
    }
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static String toAscii(byte[] data, int start, int end) {
    StringBuilder sb = new StringBuilder(Math.max(0, end - start));
    for (int i = start; i < end; i++) {
      int c = data[i] & 0xFF;
      if (c >= 32 && c < 127) {
        sb.append((char) c);
      } else {
        sb.append('.');
      }
    }
    return sb.toString();
  }

  private static Instant toInstant(long timestampMicros) {
    long seconds = Math.floorDiv(timestampMicros, 1_000_000L);
    long micros = Math.floorMod(timestampMicros, 1_000_000L);
    return Instant.ofEpochSecond(seconds, micros * 1_000L);
  }
}
