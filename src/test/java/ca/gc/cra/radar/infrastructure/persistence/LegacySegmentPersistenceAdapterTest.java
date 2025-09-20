package ca.gc.cra.radar.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.persistence.legacy.LegacySegmentIO;
import ca.gc.cra.radar.infrastructure.persistence.legacy.SegmentRecordMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LegacySegmentPersistenceAdapterTest {
  @Test
  void writesRequestAndResponseSegments() throws Exception {
    Path tempDir = Files.createTempDirectory("legacy-adapter-test");
    LegacySegmentPersistenceAdapter adapter = new LegacySegmentPersistenceAdapter(tempDir);

    FiveTuple flow = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
    ByteStream requestStream =
        new ByteStream(flow, true, "GET /\n".getBytes(StandardCharsets.US_ASCII), 10L);
    ByteStream responseStream =
        new ByteStream(flow, false, "HTTP/1.1 200\n".getBytes(StandardCharsets.US_ASCII), 20L);

    MessageEvent request =
        new MessageEvent(
            ProtocolId.HTTP, MessageType.REQUEST, requestStream, MessageMetadata.empty());
    MessageEvent response =
        new MessageEvent(
            ProtocolId.HTTP, MessageType.RESPONSE, responseStream, MessageMetadata.empty());

    adapter.persist(new MessagePair(request, response));
    adapter.close();

    try (LegacySegmentIO.Reader reader = new LegacySegmentIO.Reader(tempDir)) {
      var first = SegmentRecordMapper.fromLegacy(reader.next());
      var second = SegmentRecordMapper.fromLegacy(reader.next());

      assertEquals("10.0.0.1", first.srcIp());
      assertEquals("10.0.0.2", first.dstIp());
      assertEquals(10L, first.timestampMicros());
      assertArrayEquals(requestStream.data(), first.payload());

      assertEquals("10.0.0.2", second.srcIp());
      assertEquals("10.0.0.1", second.dstIp());
      assertEquals(20L, second.timestampMicros());
      assertArrayEquals(responseStream.data(), second.payload());
    }
  }
}
