package ca.gc.cra.radar.api.tools;

import ca.gc.cra.radar.api.CliArgsParser;
import ca.gc.cra.radar.api.CliInput;
import ca.gc.cra.radar.api.CliPrinter;
import ca.gc.cra.radar.api.ExitCode;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.infrastructure.persistence.SegmentIoAdapter;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI utility that scans recorded segment files for a byte sequence and prints matches with context.
 */
public final class SegbinGrepCli {
  private static final Logger log = LoggerFactory.getLogger(SegbinGrepCli.class);
  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault());
  private static final String SUMMARY_USAGE =
      "usage: segbingrep needle=STRING [in=./cap-out ctx=32] -- scans segment binaries for a literal byte sequence";
  private static final String HELP_TEXT = """
      RADAR segbin grep utility

      Usage:
        segbingrep needle=STRING [in=./cap-out ctx=32]

      Required options:
        needle=STRING              Literal byte sequence to search for (ISO-8859-1)

      Common options:
        in=PATH                    Segment directory to search (default ./cap-out)
        ctx=N                      Context bytes shown around the match (default 32)
        --help                     Show detailed help
        --verbose                  Enable DEBUG logging for troubleshooting

      Example:
        segbingrep needle=login in=./cap-out ctx=16
      """;

  private SegbinGrepCli() {}

  /**
   * Entry point invoked by the JVM.
   *
   * @param args raw CLI arguments
   */
  public static void main(String[] args) {
    ExitCode exit = run(args);
    System.exit(exit.code());
  }

  /**
   * Executes the CLI utility and returns the resulting exit code.
   *
   * @param args command-line arguments
   * @return exit code for the grep operation
   */
  public static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);
    if (input.help()) {
      CliPrinter.println(HELP_TEXT.stripTrailing());
      return ExitCode.SUCCESS;
    }
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for segbingrep CLI");
    }

    Map<String, String> kv = CliArgsParser.toMap(input.keyValueArgs());
    String inputDir = kv.getOrDefault("in", "./cap-out");
    String needle = kv.getOrDefault("needle", "");
    int ctx = parseInt(kv.getOrDefault("ctx", "32"));

    if (needle == null || needle.isBlank()) {
      log.error("Missing required argument needle=STRING");
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
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
          log.info(
              "{} GREP {}:{} -> {}:{} seq={} len={} off={}",
              TS.format(toInstant(record.timestampMicros())),
              record.srcIp(),
              record.srcPort(),
              record.dstIp(),
              record.dstPort(),
              record.sequence(),
              payload.length,
              offset);
          log.info("        \"{}[{}]{}\"", left, mid, right);
        }
      }
    } catch (IOException ex) {
      log.error("Failed to scan segments in {} due to I/O error", inputDir, ex);
      return ExitCode.IO_ERROR;
    } catch (IllegalArgumentException ex) {
      log.error("Invalid segbingrep configuration: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Segbin grep interrupted", ex);
      return ExitCode.INTERRUPTED;
    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure during segbingrep", ex);
      return ExitCode.RUNTIME_FAILURE;
    } catch (Exception ex) {
      log.error("Unexpected checked exception during segbingrep", ex);
      return ExitCode.RUNTIME_FAILURE;
    }

    log.info("Scanned %,d segments, matches: %,d", total, hits);
    return ExitCode.SUCCESS;
  }

  /**
   * Parses the ctx argument, defaulting to 32 on invalid input.
   *
   * @param s ctx string supplied by the user
   * @return parsed integer context window
   */
  private static int parseInt(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException ex) {
      log.warn("Invalid ctx value '{}'; defaulting to 32", s);
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
