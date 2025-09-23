package ca.gc.cra.radar.e2e;

import ca.gc.cra.radar.api.ExitCode;
import ca.gc.cra.radar.api.Main;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs offline capture-to-poster flows for HTTP and TN3270 PCAP fixtures and records the outputs.
 */
public class EndToEndPcapIT {

  private static final DateTimeFormatter TS_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
  private static final String TS = ZonedDateTime.now(ZoneId.systemDefault()).format(TS_FORMATTER);
  private static final Path ROOT = Paths.get("target", "e2e", TS);
  private static final Pattern SEGBIN_PATTERN = Pattern.compile(".*\\.segbin$");
  private static final Method MAIN_RUN = locateMainRun();

  @Test
  void httpEndToEnd() throws Exception {
    Path pcap = resource("pcap/http_small.pcap");
    Assumptions.assumeTrue(Files.exists(pcap), "HTTP PCAP missing");

    ProtocolPaths paths = protocolPaths("http");
    Path captureDir = paths.captureDir();
    Path assembleDir = paths.assembleDir();
    Path posterDir = paths.posterDir();

    ExitCode captureCode =
        runRadar(
            "capture",
            "--enable-bpf",
            "pcapFile=" + pcap.toAbsolutePath(),
            "out=" + captureDir.toAbsolutePath(),
            "httpOut=" + captureDir.resolve("http-poster").toAbsolutePath(),
            "tnOut=" + captureDir.resolve("tn-poster").toAbsolutePath(),
            "fileBase=capture-http-" + TS,
            "rollMiB=64",
            "protocol=GENERIC",
            "ioMode=FILE",
            "bpf=tcp and port 80");
    assertSuccess("HTTP capture", captureCode);

    long segbinCount = countMatchingFiles(captureDir, SEGBIN_PATTERN);
    Assertions.assertTrue(segbinCount > 0, "Expected capture stage to create .segbin files");

    Path httpAssembleOut = assembleDir.resolve("http");
    Path tnAssembleOut = assembleDir.resolve("tn3270");
    ExitCode assembleCode =
        runRadar(
            "assemble",
            "in=" + captureDir.toAbsolutePath(),
            "out=" + assembleDir.toAbsolutePath(),
            "httpOut=" + httpAssembleOut.toAbsolutePath(),
            "tnOut=" + tnAssembleOut.toAbsolutePath(),
            "httpEnabled=true",
            "tnEnabled=false",
            "ioMode=FILE");
    assertSuccess("HTTP assemble", assembleCode);

    long assembledCount = countRegularFiles(httpAssembleOut);
    Assertions.assertTrue(assembledCount > 0, "Expected HTTP assemble output files");

    Path httpPosterOut = posterDir.resolve("http");
    ExitCode posterCode =
        runRadar(
            "poster",
            "httpIn=" + httpAssembleOut.toAbsolutePath(),
            "httpOut=" + httpPosterOut.toAbsolutePath(),
            "decode=all",
            "ioMode=FILE",
            "posterOutMode=FILE");
    assertSuccess("HTTP poster", posterCode);

    long posterCount = countRegularFiles(httpPosterOut);
    Assertions.assertTrue(posterCount > 0, "Expected HTTP poster output files");

    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("captureSegbinCount", segbinCount);
    counts.put("assembleHttpCount", assembledCount);
    counts.put("posterHttpCount", posterCount);

    writeSummary(
        paths.root().resolve("SUMMARY-HTTP.txt"),
        counts,
        List.of(captureDir, httpAssembleOut, httpPosterOut));
  }

  @Test
  void tn3270EndToEnd() throws Exception {
    Path pcap = resource("pcap/tn3270_small.pcap");
    Assumptions.assumeTrue(Files.exists(pcap), "TN3270 PCAP missing");

    ProtocolPaths paths = protocolPaths("tn3270");
    Path captureDir = paths.captureDir();
    Path assembleDir = paths.assembleDir();
    Path posterDir = paths.posterDir();

    ExitCode captureCode =
        runRadar(
            "capture",
            "--enable-bpf",
            "pcapFile=" + pcap.toAbsolutePath(),
            "out=" + captureDir.toAbsolutePath(),
            "httpOut=" + captureDir.resolve("http-poster").toAbsolutePath(),
            "tnOut=" + captureDir.resolve("tn-poster").toAbsolutePath(),
            "fileBase=capture-tn3270-" + TS,
            "rollMiB=64",
            "protocol=TN3270",
            "ioMode=FILE",
            "bpf=tcp and (port 23 or port 992)");
    assertSuccess("TN3270 capture", captureCode);

    long segbinCount = countMatchingFiles(captureDir, SEGBIN_PATTERN);
    Assertions.assertTrue(segbinCount > 0, "Expected capture stage to create .segbin files");

    Path httpAssembleOut = assembleDir.resolve("http");
    Path tnAssembleOut = assembleDir.resolve("tn3270");
    ExitCode assembleCode =
        runRadar(
            "assemble",
            "in=" + captureDir.toAbsolutePath(),
            "out=" + assembleDir.toAbsolutePath(),
            "httpOut=" + httpAssembleOut.toAbsolutePath(),
            "tnOut=" + tnAssembleOut.toAbsolutePath(),
            "httpEnabled=false",
            "tnEnabled=true",
            "ioMode=FILE");
    assertSuccess("TN3270 assemble", assembleCode);

    long assembledCount = countRegularFiles(tnAssembleOut);
    Assertions.assertTrue(assembledCount > 0, "Expected TN3270 assemble output files");

    Path tnPosterOut = posterDir.resolve("tn3270");
    ExitCode posterCode =
        runRadar(
            "poster",
            "tnIn=" + tnAssembleOut.toAbsolutePath(),
            "tnOut=" + tnPosterOut.toAbsolutePath(),
            "decode=all",
            "ioMode=FILE",
            "posterOutMode=FILE");
    assertSuccess("TN3270 poster", posterCode);

    long posterCount = countRegularFiles(tnPosterOut);
    Assertions.assertTrue(posterCount > 0, "Expected TN3270 poster output files");

    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("captureSegbinCount", segbinCount);
    counts.put("assembleTn3270Count", assembledCount);
    counts.put("posterTn3270Count", posterCount);

    writeSummary(
        paths.root().resolve("SUMMARY-TN3270.txt"),
        counts,
        List.of(captureDir, tnAssembleOut, tnPosterOut));
  }

  private static Method locateMainRun() {
    try {
      Method method = Main.class.getDeclaredMethod("run", String[].class);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException ex) {
      throw new IllegalStateException("RADAR Main.run(String[]) method missing", ex);
    }
  }

  private static ProtocolPaths protocolPaths(String protocolKey) throws IOException {
    Path protocolRoot = ROOT.resolve(protocolKey);
    Path captureDir = protocolRoot.resolve("cap-" + TS);
    Path assembleDir = protocolRoot.resolve("asm-" + TS);
    Path posterDir = protocolRoot.resolve("post-" + TS);
    ensureDir(protocolRoot);
    ensureDir(captureDir);
    ensureDir(assembleDir);
    ensureDir(posterDir);
    return new ProtocolPaths(protocolRoot, captureDir, assembleDir, posterDir);
  }

  private static Path resource(String relative) {
    return Paths.get("src", "test", "resources").resolve(relative);
  }

  private static void ensureDir(Path path) throws IOException {
    Files.createDirectories(path);
  }

  private static ExitCode runRadar(String... args) {
    try {
      return (ExitCode) MAIN_RUN.invoke(null, new Object[] {args});
    } catch (IllegalAccessException ex) {
      throw new IllegalStateException("Unable to access RADAR CLI entry point", ex);
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("RADAR CLI execution failed", cause);
    }
  }

  private static void assertSuccess(String stage, ExitCode code) {
    Assertions.assertEquals(
        ExitCode.SUCCESS, code, () -> stage + " stage failed with exit code " + code);
  }

  private static long countMatchingFiles(Path dir, Pattern pattern) throws IOException {
    if (!Files.exists(dir)) {
      return 0L;
    }
    try (Stream<Path> stream = Files.walk(dir)) {
      return stream
          .filter(Files::isRegularFile)
          .map(Path::getFileName)
          .filter(Objects::nonNull)
          .map(Path::toString)
          .filter(name -> pattern.matcher(name).matches())
          .count();
    }
  }

  private static long countRegularFiles(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return 0L;
    }
    try (Stream<Path> stream = Files.walk(dir)) {
      return stream.filter(Files::isRegularFile).count();
    }
  }

  private static void writeSummary(Path summaryFile, Map<String, Long> counts, List<Path> keyDirs)
      throws IOException {
    Path parent = summaryFile.getParent();
    if (parent != null) {
      ensureDir(parent);
    }
    StringBuilder builder = new StringBuilder("# E2E Summary\n");
    builder.append("timestamp=").append(TS).append('\n');
    for (Path dir : keyDirs) {
      builder.append("dir=").append(dir.toAbsolutePath()).append('\n');
    }
    for (Map.Entry<String, Long> entry : counts.entrySet()) {
      builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
    }
    Files.writeString(summaryFile, builder.toString(), StandardCharsets.UTF_8);
  }

  private record ProtocolPaths(Path root, Path captureDir, Path assembleDir, Path posterDir) {}
}
