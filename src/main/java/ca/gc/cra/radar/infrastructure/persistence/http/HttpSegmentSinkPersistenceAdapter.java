package ca.gc.cra.radar.infrastructure.persistence.http;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.TransactionId;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.persistence.io.BlobWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Writes HTTP message pairs in the legacy SegmentSink blob/index format.
 * <p>Synchronizes writes to append request/response payloads to a shared blob and emits matching
 * index entries. Optionally delegates non-HTTP pairs to another {@link PersistencePort}.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class HttpSegmentSinkPersistenceAdapter implements PersistencePort {
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final BlobWriter blob;
  private final BufferedWriter index;
  private final String blobName;
  private final String indexName;
  private final PersistencePort fallback;

  /**
   * Creates an HTTP segment sink without a fallback.
   *
   * @param directory output directory for blob/index files
   * @since RADAR 0.1-doc
   */
  public HttpSegmentSinkPersistenceAdapter(Path directory) {
    this(directory, null);
  }

  /**
   * Creates an HTTP segment sink with an optional fallback for non-HTTP pairs.
   *
   * @param directory output directory for blob/index files
   * @param fallback optional persistence fallback for other protocols
   * @since RADAR 0.1-doc
   */
  public HttpSegmentSinkPersistenceAdapter(Path directory, PersistencePort fallback) {
    try {
      Files.createDirectories(directory);
      String stamp = STAMP.format(Instant.now());
      this.blobName = "blob-" + stamp + ".seg";
      this.indexName = "index-" + stamp + ".ndjson";
      this.blob = new BlobWriter(directory.resolve(blobName));
      this.index = Files.newBufferedWriter(
          directory.resolve(indexName),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      this.fallback = fallback;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to initialize HTTP segment sink", e);
    }
  }

  /**
   * Writes HTTP request/response payloads to disk, delegating other protocols to the fallback.
   *
   * @param pair message pair to persist; {@code null} is ignored
   * @throws Exception if writing fails or the fallback throws
   * @since RADAR 0.1-doc
   */
  @Override
  public synchronized void persist(MessagePair pair) throws Exception {
    if (pair == null) {
      return;
    }
    boolean handled = false;
    if (isHttp(pair.request())) {
      writeEvent(pair.request(), "REQ");
      handled = true;
    }
    if (isHttp(pair.response())) {
      writeEvent(pair.response(), "RSP");
      handled = true;
    }
    if (!handled && fallback != null) {
      fallback.persist(pair);
    }
  }

  private static boolean isHttp(MessageEvent event) {
    return event != null && event.protocol() == ProtocolId.HTTP && event.payload() != null;
  }

  private void writeEvent(MessageEvent event, String kind) throws Exception {
    ByteStream stream = event.payload();
    byte[] data = stream.data();
    int headersEnd = findHeadersEnd(data);
    int headerLen = headersEnd >= 0 ? headersEnd : data.length;
    int bodyOffset = headerLen;
    int bodyLen = Math.max(0, data.length - bodyOffset);

    FiveTuple flow = stream.flow();
    String src = endpoint(flow, stream.fromClient());
    String dst = endpoint(flow, !stream.fromClient());

    MessageMetadata metadata = event.metadata() != null ? event.metadata() : MessageMetadata.empty();
    String transactionId = metadata.transactionId() != null && !metadata.transactionId().isBlank()
        ? metadata.transactionId()
        : TransactionId.newId();
    Map<String, String> attrs = metadata.attributes();
    String session = attrs != null ? attrs.get("session") : null;

    long headersOffset = blob.position();
    blob.write(data, 0, headerLen);
    long headersLength = blob.position() - headersOffset;

    long bodyDiskOffset = blob.position();
    if (bodyLen > 0) {
      blob.write(data, bodyOffset, bodyLen);
    }
    long bodyLength = blob.position() - bodyDiskOffset;

    String firstLine = extractFirstLine(data, headerLen);
    String sessionJson = session == null ? "null" : ('"' + escape(session) + '"');

    StringBuilder json = new StringBuilder(192)
        .append('{')
        .append("\"ts_first\":").append(stream.timestampMicros()).append(',')
        .append("\"ts_last\":").append(stream.timestampMicros()).append(',')
        .append("\"id\":\"").append(escape(transactionId)).append("\",")
        .append("\"kind\":\"").append(kind).append("\",")
        .append("\"session\":").append(sessionJson).append(',')
        .append("\"src\":\"").append(escape(src)).append("\",")
        .append("\"dst\":\"").append(escape(dst)).append("\",")
        .append("\"first_line\":\"").append(escape(firstLine)).append("\",")
        .append("\"headers_blob\":\"").append(blobName).append("\",")
        .append("\"headers_off\":").append(headersOffset).append(',')
        .append("\"headers_len\":").append(headersLength).append(',')
        .append("\"body_blob\":\"").append(blobName).append("\",")
        .append("\"body_off\":").append(bodyDiskOffset).append(',')
        .append("\"body_len\":").append(bodyLength)
        .append('}');

    index.write(json.toString());
    index.newLine();
    index.flush();
    blob.flush(false);
  }

  private static int findHeadersEnd(byte[] data) {
    for (int i = 0; data != null && i + 3 < data.length; i++) {
      if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
        return i + 4;
      }
    }
    return -1;
  }

  private static String endpoint(FiveTuple flow, boolean fromClient) {
    return fromClient ? flow.srcIp() + ':' + flow.srcPort() : flow.dstIp() + ':' + flow.dstPort();
  }

  private static String extractFirstLine(byte[] data, int limit) {
    if (data == null || data.length == 0) {
      return "";
    }
    int end = Math.min(limit, data.length);
    for (int i = 0; i + 1 < end; i++) {
      if (data[i] == '\r' && data[i + 1] == '\n') {
        return new String(data, 0, i, StandardCharsets.ISO_8859_1).trim();
      }
    }
    return new String(data, 0, end, StandardCharsets.ISO_8859_1).trim();
  }

  private static String escape(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"', '\\' -> out.append('\\').append(c);
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(' ');
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  /**
   * Flushes and closes blob/index streams and the fallback sink.
   *
   * @throws Exception if closing the fallback fails
   * @since RADAR 0.1-doc
   */
  @Override
  public synchronized void close() throws Exception {
    try {
      index.flush();
    } finally {
      try {
        index.close();
      } finally {
        blob.close();
        if (fallback != null) {
          fallback.close();
        }
      }
    }
  }
}
