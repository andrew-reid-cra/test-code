package ca.gc.cra.radar.infrastructure.persistence.segment;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessagePair;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple NDJSON sink for message pairs used during migration from legacy tooling.
 * <p>Writes each pair to {@code pairs.ndjson}, overwriting the previous contents.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class SegmentSinkAdapter implements PersistencePort {
  private final Path directory;

  /**
   * Creates an adapter that writes under the supplied directory.
   *
   * @param directory target directory (created as needed)
   * @throws NullPointerException if {@code directory} is {@code null}
   * @since RADAR 0.1-doc
   */
  public SegmentSinkAdapter(Path directory) {
    this.directory = directory;
  }

  /**
   * Serializes the pair as a minimal JSON object and writes it to {@code pairs.ndjson}.
   *
   * @param pair message pair to persist; {@code null} is ignored
   * @throws IOException if the file cannot be written
   * @implNote Overwrites the file on each invocation; only suitable for debugging.
   * @since RADAR 0.1-doc
   */
  @Override
  public void persist(MessagePair pair) throws IOException {
    if (pair == null) return;
    Files.createDirectories(directory);
    Path target = directory.resolve("pairs.ndjson");
    Files.writeString(target, serialize(pair) + "\n", StandardCharsets.UTF_8);
  }

  private static String serialize(MessagePair pair) {
    return "{" + "\"protocol\":\"" + pair.request().protocol() + "\"}";
  }

  /**
   * Closes the sink. No-op for this adapter.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() throws Exception {}
}
