package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.persistence.http.HttpSegmentSinkPersistenceAdapter;
import ca.gc.cra.radar.infrastructure.persistence.tn3270.Tn3270SegmentSinkPersistenceAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PosterUseCaseTest {

  @Test
  void rendersHttpPairToTextFile() throws Exception {
    Path inputDir = Files.createTempDirectory("poster-http-in");
    Path outputDir = Files.createTempDirectory("poster-http-out");

    try (HttpSegmentSinkPersistenceAdapter adapter = new HttpSegmentSinkPersistenceAdapter(inputDir)) {
      MessagePair pair = httpPair();
      adapter.persist(pair);
    }

    PosterConfig config = PosterConfig.fromMap(Map.of(
        "in", inputDir.toString(),
        "out", outputDir.toString(),
        "decode", "none"));

    new PosterUseCase().run(config);

    try (Stream<Path> files = Files.list(outputDir)) {
      List<Path> outputs = files.toList();
      assertTrue(!outputs.isEmpty(), "expected poster output file");
      String content = Files.readString(outputs.get(0), StandardCharsets.UTF_8);
      assertTrue(content.contains("=== HTTP REQUEST ==="));
      assertTrue(content.contains("GET /demo"));
      assertTrue(content.contains("=== HTTP RESPONSE ==="));
      assertTrue(content.contains("200"));
    }
  }

  @Test
  void rendersTnPairAsHexDump() throws Exception {
    Path inputDir = Files.createTempDirectory("poster-tn-in");
    Path outputDir = Files.createTempDirectory("poster-tn-out");

    try (Tn3270SegmentSinkPersistenceAdapter adapter = new Tn3270SegmentSinkPersistenceAdapter(inputDir)) {
      MessagePair pair = tnPair();
      adapter.persist(pair);
    }

    PosterConfig config = PosterConfig.fromMap(Map.of(
        "in", inputDir.toString(),
        "out", outputDir.toString()));

    new PosterUseCase().run(config);

    try (Stream<Path> files = Files.list(outputDir)) {
      List<Path> outputs = files.toList();
      assertTrue(!outputs.isEmpty(), "expected TN output file");
      String content = Files.readString(outputs.get(0), StandardCharsets.UTF_8);
      assertTrue(content.contains("=== TN3270 REQUEST ==="));
      assertTrue(content.contains("f1 f2"));
    }
  }

  private static MessagePair httpPair() {
    FiveTuple flow = new FiveTuple("10.1.1.1", 1234, "10.1.1.2", 80, "TCP");
    byte[] requestBytes = "GET /demo HTTP/1.1\r\nHost: example\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    byte[] responseBytes = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.ISO_8859_1);
    MessageEvent request = new MessageEvent(
        ProtocolId.HTTP,
        MessageType.REQUEST,
        new ByteStream(flow, true, requestBytes, 10L),
        new MessageMetadata("txn-http", Map.of()));
    MessageEvent response = new MessageEvent(
        ProtocolId.HTTP,
        MessageType.RESPONSE,
        new ByteStream(flow, false, responseBytes, 20L),
        new MessageMetadata("txn-http", Map.of()));
    return new MessagePair(request, response);
  }

  private static MessagePair tnPair() {
    FiveTuple flow = new FiveTuple("10.2.2.1", 992, "10.2.2.2", 23, "TCP");
    byte[] rsp = new byte[] {(byte) 0xF1, (byte) 0xF2, 0x10};
    byte[] req = new byte[] {(byte) 0x7E, (byte) 0xFF};
    MessageEvent response = new MessageEvent(
        ProtocolId.TN3270,
        MessageType.RESPONSE,
        new ByteStream(flow, false, rsp, 30L),
        new MessageMetadata("txn-tn", Map.of()));
    MessageEvent request = new MessageEvent(
        ProtocolId.TN3270,
        MessageType.REQUEST,
        new ByteStream(flow, true, req, 40L),
        new MessageMetadata("txn-tn", Map.of()));
    return new MessagePair(request, response);
  }
}
