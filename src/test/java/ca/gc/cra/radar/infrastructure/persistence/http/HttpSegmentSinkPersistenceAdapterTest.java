package ca.gc.cra.radar.infrastructure.persistence.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HttpSegmentSinkPersistenceAdapterTest {
  @Test
  void writesIndexAndBlobEntriesForRequestResponse() throws Exception {
    Path tempDir = Files.createTempDirectory("http-sink-test");
    HttpSegmentSinkPersistenceAdapter adapter = new HttpSegmentSinkPersistenceAdapter(tempDir);

    FiveTuple flow = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
    byte[] requestBytes =
        "POST /demo HTTP/1.1\r\nHost: example\r\nContent-Length: 3\r\n\r\nfoo".getBytes(StandardCharsets.US_ASCII);
    byte[] responseBytes =
        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.US_ASCII);

    MessageEvent request =
        new MessageEvent(
            ProtocolId.HTTP,
            MessageType.REQUEST,
            new ByteStream(flow, true, requestBytes, 10L),
            new MessageMetadata("req-id", Map.of()));
    MessageEvent response =
        new MessageEvent(
            ProtocolId.HTTP,
            MessageType.RESPONSE,
            new ByteStream(flow, false, responseBytes, 20L),
            new MessageMetadata("req-id", Map.of()));

    adapter.persist(new MessagePair(request, response));
    adapter.close();

    Path indexPath =
        Files.list(tempDir)
            .filter(p -> p.getFileName().toString().startsWith("index-"))
            .findFirst()
            .orElseThrow();
    Path blobPath =
        Files.list(tempDir)
            .filter(p -> p.getFileName().toString().startsWith("blob-"))
            .findFirst()
            .orElseThrow();

    List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
    assertEquals(2, lines.size());
    String reqLine = lines.get(0);
    String rspLine = lines.get(1);

    assertEquals("REQ", extractString(reqLine, "kind"));
    assertEquals("RSP", extractString(rspLine, "kind"));
    assertEquals("req-id", extractString(reqLine, "id"));
    assertEquals("req-id", extractString(rspLine, "id"));
    assertEquals("10.0.0.1:1234", extractString(reqLine, "src"));
    assertEquals("10.0.0.2:80", extractString(reqLine, "dst"));
    assertEquals("10.0.0.2:80", extractString(rspLine, "src"));
    assertEquals("10.0.0.1:1234", extractString(rspLine, "dst"));

    long reqHeadersOff = extractLong(reqLine, "headers_off");
    long reqHeadersLen = extractLong(reqLine, "headers_len");
    long reqBodyOff = extractLong(reqLine, "body_off");
    long reqBodyLen = extractLong(reqLine, "body_len");

    long rspBodyOff = extractLong(rspLine, "body_off");
    long rspBodyLen = extractLong(rspLine, "body_len");

    try (FileChannel channel = FileChannel.open(blobPath, StandardOpenOption.READ)) {
      byte[] reqHeaders = read(channel, reqHeadersOff, reqHeadersLen);
      assertTrue(new String(reqHeaders, StandardCharsets.ISO_8859_1).startsWith("POST /demo"));

      byte[] reqBody = read(channel, reqBodyOff, reqBodyLen);
      assertArrayEquals("foo".getBytes(StandardCharsets.US_ASCII), reqBody);

      byte[] rspBody = read(channel, rspBodyOff, rspBodyLen);
      assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), rspBody);
    }
  }

  private static String extractString(String json, String key) {
    Matcher m = Pattern.compile("\\\"" + key + "\\\":\\\"([^\\\"]*)\\\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static long extractLong(String json, String key) {
    Matcher m = Pattern.compile("\\\"" + key + "\\\":(\\d+)").matcher(json);
    if (!m.find()) {
      throw new AssertionError("Missing numeric field " + key + " in " + json);
    }
    return Long.parseLong(m.group(1));
  }

  private static byte[] read(FileChannel channel, long offset, long length) throws Exception {
    ByteBuffer buffer = ByteBuffer.allocate((int) length);
    channel.read(buffer, offset);
    return buffer.array();
  }
}

