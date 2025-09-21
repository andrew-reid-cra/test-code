package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessagePair;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Temporary adapter writing message pairs as NDJSON entries. Real SegmentSink wiring will replace this.
 * <p>Not thread-safe; each instance writes to {@code pairs.ndjson} in the configured directory.
 *
 * @since RADAR 0.1-doc
 */
public final class SegmentSinkAdapter implements PersistencePort {
  private final Path directory;

  /**
   * Creates a sink that writes pairs to an NDJSON file.
   *
   * @param directory target directory for the NDJSON file
   * @throws NullPointerException if {@code directory} is {@code null}
   * @since RADAR 0.1-doc
   */
  public SegmentSinkAdapter(Path directory) {
    this.directory = directory;
  }

  /**
   * Writes the pair as a single NDJSON line appended to the file.
   *
   * @param pair pair to serialize; {@code null} is ignored
   * @throws IOException if the file cannot be written
   * @implNote Appends to the file using CREATE+APPEND to preserve previous entries.
   * @since RADAR 0.1-doc
   */
  @Override
  public void persist(MessagePair pair) throws IOException {
    if (pair == null) {
      return;
    }
    Files.createDirectories(directory);
    Path file = directory.resolve("pairs.ndjson");
    Files.writeString(
        file,
        serialize(pair) + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private static String serialize(MessagePair pair) {
    return "{" + "\"protocol\":\"" + pair.request().protocol() + "\"}";
  }

  /**
   * No-op close; file IO is performed per write.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() throws Exception {}
}







