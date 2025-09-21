package ca.gc.cra.radar.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentSinkAdapterNdjsonTest {
  @TempDir Path dir;

  @Test
  void persistAppendsEachEntry() throws Exception {
    SegmentSinkAdapter adapter = new SegmentSinkAdapter(dir);
    MessagePair first = new MessagePair(messageFor(ProtocolId.HTTP), null);
    MessagePair second = new MessagePair(messageFor(ProtocolId.TN3270), null);

    adapter.persist(first);
    adapter.persist(second);

    Path file = dir.resolve("pairs.ndjson");
    assertTrue(Files.exists(file), "Expected file to be created");
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertEquals(
        List.of("{\"protocol\":\"HTTP\"}", "{\"protocol\":\"TN3270\"}"), lines);
  }

  private static MessageEvent messageFor(ProtocolId protocol) {
    FiveTuple flow = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
    ByteStream bytes = new ByteStream(flow, true, protocol.name().getBytes(StandardCharsets.UTF_8), 42L);
    return new MessageEvent(protocol, MessageType.REQUEST, bytes, MessageMetadata.empty());
  }
}



