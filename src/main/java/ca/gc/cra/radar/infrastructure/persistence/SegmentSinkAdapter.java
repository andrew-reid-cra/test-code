package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessagePair;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Temporary adapter writing message pairs as NDJSON entries. Real SegmentSink wiring will replace this.
 */
public final class SegmentSinkAdapter implements PersistencePort {
  private final Path directory;

  public SegmentSinkAdapter(Path directory) {
    this.directory = directory;
  }

  @Override
  public void persist(MessagePair pair) throws IOException {
    if (pair == null) return;
    Files.createDirectories(directory);
    Path file = directory.resolve("pairs.ndjson");
    Files.writeString(file, serialize(pair) + "\n", StandardCharsets.UTF_8);
  }

  private static String serialize(MessagePair pair) {
    return "{" + "\"protocol\":\"" + pair.request().protocol() + "\"}";
  }

  @Override
  public void close() throws Exception {}
}


