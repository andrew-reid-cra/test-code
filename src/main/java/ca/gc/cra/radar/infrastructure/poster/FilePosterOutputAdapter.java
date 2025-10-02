package ca.gc.cra.radar.infrastructure.poster;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes poster reports to the filesystem using the legacy naming convention.
 * <p>Synchronized to avoid clashing writes when multiple reports arrive concurrently.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class FilePosterOutputAdapter implements PosterOutputPort {
  private final Path outputDirectory;
  private final String extension;

  /**
   * Creates a file-backed poster output adapter.
   *
   * @param outputDirectory directory for rendered poster files
   * @param protocol protocol associated with the rendered reports
   * @throws NullPointerException if {@code outputDirectory} or {@code protocol} is {@code null}
   * @since RADAR 0.1-doc
   */
  public FilePosterOutputAdapter(Path outputDirectory, ProtocolId protocol) {
    this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
    this.extension = determineExtension(Objects.requireNonNull(protocol, "protocol"));
  }

  /**
   * Writes the poster report contents to a file named after the transaction id.
   *
   * @param report poster report to write; must not be {@code null}
   * @throws Exception if the file cannot be created or written
   * @implNote Names files as {@code <timestamp>_<txId>.<ext>} matching legacy tooling.
   * @since RADAR 0.1-doc
   */
  @Override
  public synchronized void write(PosterReport report) throws Exception {
    Objects.requireNonNull(report, "report");
    ensureDirectory();
    String safeId = sanitize(report.txId());
    String fileName = report.timestampMicros() + "_" + safeId + extension;
    Path outFile = outputDirectory.resolve(fileName);
    Files.writeString(outFile, report.content(), StandardCharsets.UTF_8);
  }

  private void ensureDirectory() throws IOException {
    if (!Files.exists(outputDirectory)) {
      Files.createDirectories(outputDirectory);
    }
  }

  private static String determineExtension(ProtocolId protocol) {
    return protocol == ProtocolId.HTTP ? ".http" : ".tn3270.txt";
  }

  private static String sanitize(String id) {
    StringBuilder sb = new StringBuilder(Math.max(16, id.length()));
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
        sb.append(c);
      } else {
        sb.append('_');
      }
    }
    if (sb.length() == 0) {
      sb.append('x');
    }
    return sb.toString();
  }
}


