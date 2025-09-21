package ca.gc.cra.radar.infrastructure.poster;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilePosterOutputAdapterTest {

  @Test
  void createsDirectoryAndWritesHttpReport() throws Exception {
    Path tempDir = Files.createTempDirectory("poster-http");
    Path output = tempDir.resolve("reports");
    try {
      FilePosterOutputAdapter adapter = new FilePosterOutputAdapter(output, ProtocolId.HTTP);
      PosterReport report = new PosterReport(ProtocolId.HTTP, "txn", 42L, "payload");

      adapter.write(report);

      Path written = findSingleFile(output);
      assertEquals("42_txn.http", written.getFileName().toString());
      assertEquals("payload", Files.readString(written, StandardCharsets.UTF_8));
    } finally {
      deleteRecursively(tempDir);
    }
  }

  @Test
  void choosesExtensionBasedOnProtocol() throws Exception {
    Path tempDir = Files.createTempDirectory("poster-ext");
    try {
      FilePosterOutputAdapter http = new FilePosterOutputAdapter(tempDir, ProtocolId.HTTP);
      FilePosterOutputAdapter tn = new FilePosterOutputAdapter(tempDir, ProtocolId.TN3270);
      http.write(new PosterReport(ProtocolId.HTTP, "http", 1L, "a"));
      tn.write(new PosterReport(ProtocolId.TN3270, "tn", 2L, "b"));

      List<String> names = Files.list(tempDir)
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
      assertTrue(names.contains("1_http.http"));
      assertTrue(names.contains("2_tn.tn3270.txt"));
    } finally {
      deleteRecursively(tempDir);
    }
  }

  @Test
  void sanitizesIllegalCharactersInTransactionIds() throws Exception {
    Path tempDir = Files.createTempDirectory("poster-sanitize");
    try {
      FilePosterOutputAdapter adapter = new FilePosterOutputAdapter(tempDir, ProtocolId.HTTP);
      PosterReport first = new PosterReport(ProtocolId.HTTP, "id:with*bad/chars", 10L, "x");
      PosterReport second = new PosterReport(ProtocolId.HTTP, "!!!", 11L, "y");
      adapter.write(first);
      adapter.write(second);

      List<String> names = Files.list(tempDir)
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();

      String expectedFirst = expectedFileName(first);
      String expectedSecond = expectedFileName(second);
      assertTrue(names.contains(expectedFirst), names::toString);
      assertTrue(names.contains(expectedSecond), names::toString);
    } finally {
      deleteRecursively(tempDir);
    }
  }

  @Test
  void supportsRepeatedWrites() throws Exception {
    Path tempDir = Files.createTempDirectory("poster-repeat");
    try {
      FilePosterOutputAdapter adapter = new FilePosterOutputAdapter(tempDir, ProtocolId.HTTP);
      adapter.write(new PosterReport(ProtocolId.HTTP, "a", 1L, "first"));
      adapter.write(new PosterReport(ProtocolId.HTTP, "b", 2L, "second"));

      List<String> contents = Files.list(tempDir)
          .sorted(Comparator.comparing(Path::toString))
          .map(FilePosterOutputAdapterTest::read)
          .toList();
      assertEquals(List.of("first", "second"), contents);
    } finally {
      deleteRecursively(tempDir);
    }
  }

  private static String expectedFileName(PosterReport report) {
    return report.timestampMicros() + "_" + sanitize(report.txId()) + ".http";
  }

  private static String sanitize(String id) {
    try {
      Method m = FilePosterOutputAdapter.class.getDeclaredMethod("sanitize", String.class);
      m.setAccessible(true);
      return (String) m.invoke(null, id);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private static Path findSingleFile(Path dir) throws IOException {
    try (var stream = Files.list(dir)) {
      return stream.findFirst().orElseThrow();
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var stream = Files.walk(root)) {
      stream.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
      });
    }
  }
}
