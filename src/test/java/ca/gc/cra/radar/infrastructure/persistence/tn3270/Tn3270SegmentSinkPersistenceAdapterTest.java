package ca.gc.cra.radar.infrastructure.persistence.tn3270;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Tn3270SegmentSinkPersistenceAdapterTest {
  @Test
  void writesBinaryPayloadToBlobAndIndex() throws Exception {
    Path dir = Files.createTempDirectory("tn3270-sink-test");
    Tn3270SegmentSinkPersistenceAdapter adapter = new Tn3270SegmentSinkPersistenceAdapter(dir);

    FiveTuple flow = new FiveTuple("198.51.100.1", 992, "203.0.113.5", 23, "TCP");
    byte[] rspBytes = new byte[] {(byte) 0xF1, (byte) 0xF2, (byte) 0x7E};
    byte[] reqBytes = new byte[] {(byte) 0x6E, (byte) 0xF8};

    MessageEvent response =
        new MessageEvent(
            ProtocolId.TN3270,
            MessageType.RESPONSE,
            new ByteStream(flow, false, rspBytes, 10L),
            new MessageMetadata("txn-1", Map.of()));
    MessageEvent request =
        new MessageEvent(
            ProtocolId.TN3270,
            MessageType.REQUEST,
            new ByteStream(flow, true, reqBytes, 11L),
            new MessageMetadata("txn-1", Map.of()));

    adapter.persist(new MessagePair(request, response));
    adapter.close();

    Path indexPath =
        Files.list(dir)
            .filter(p -> p.getFileName().toString().startsWith("index-tn-"))
            .findFirst()
            .orElseThrow();
    Path blobPath =
        Files.list(dir)
            .filter(p -> p.getFileName().toString().startsWith("blob-tn-"))
            .findFirst()
            .orElseThrow();

    List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
    assertEquals(2, lines.size());

    String reqLine = lines.stream().filter(l -> l.contains("\"kind\":\"REQ\""))
        .findFirst().orElseThrow();
    String rspLine = lines.stream().filter(l -> l.contains("\"kind\":\"RSP\""))
        .findFirst().orElseThrow();

    assertEquals("txn-1", extractString(reqLine, "id"));
    assertEquals("txn-1", extractString(rspLine, "id"));
    assertEquals("198.51.100.1:992", extractString(reqLine, "src"));
    assertEquals("203.0.113.5:23", extractString(reqLine, "dst"));
    assertEquals("203.0.113.5:23", extractString(rspLine, "src"));
    assertEquals("198.51.100.1:992", extractString(rspLine, "dst"));

    assertEquals("0", extractString(reqLine, "headers_len"));
    assertEquals("0", extractString(rspLine, "headers_len"));

    long reqBodyOff = extractLong(reqLine, "body_off");
    long reqBodyLen = extractLong(reqLine, "body_len");
    long rspBodyOff = extractLong(rspLine, "body_off");
    long rspBodyLen = extractLong(rspLine, "body_len");

    try (FileChannel channel = FileChannel.open(blobPath, StandardOpenOption.READ)) {
      byte[] reqBody = read(channel, reqBodyOff, reqBodyLen);
      assertArrayEquals(reqBytes, reqBody);
      byte[] rspBody = read(channel, rspBodyOff, rspBodyLen);
      assertArrayEquals(rspBytes, rspBody);
    }

    String preview = extractString(rspLine, "first_line");
    assertTrue(preview.startsWith("f1f2"));
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
