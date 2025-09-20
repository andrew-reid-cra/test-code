package ca.gc.cra.radar.infrastructure.persistence.segment;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessagePair;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SegmentSinkAdapter implements PersistencePort {
  private final Path directory;

  public SegmentSinkAdapter(Path directory) {
    this.directory = directory;
  }

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

  @Override
  public void close() throws Exception {}
}


