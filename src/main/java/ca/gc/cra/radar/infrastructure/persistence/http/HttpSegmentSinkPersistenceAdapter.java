package ca.gc.cra.radar.infrastructure.persistence.http;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.msg.TransactionId;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
 * index entries. Optionally delegates non-HTTP pairs to another {@link PersistencePort}.
 *
 * @since RADAR 0.1-doc
 */
public final class HttpSegmentSinkPersistenceAdapter implements PersistencePort {
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final FileChannel blob;
  private final BufferedWriter index;
  private final String blobName;
  private final String indexName;
  private final PersistencePort fallback;

  /**
   * Creates an HTTP segment sink without a fallback.
   *
   * @param directory output directory for blob/index files
   * @throws NullPointerException if {@code directory} is {@code null}
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
   * @throws NullPointerException if {@code directory} is {@code null}
   * @since RADAR 0.1-doc
   */
  public HttpSegmentSinkPersistenceAdapter(Path directory, PersistencePort fallback) {
    try {
      Files.createDirectories(directory);
      String stamp = STAMP.format(Instant.now());
      this.blobName = "blob-" + stamp + ".seg";
      this.indexName = "index-" + stamp + ".ndjson";
      this.blob =
          FileChannel.open(
              directory.resolve(blobName),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.READ);
      this.blob.position(blob.size());
      this.index =
          Files.newBufferedWriter(
              directory.resolve(indexName), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
   * @implNote Flushes headers/body metadata to disk on each call to preserve crash consistency.
   * @since RADAR 0.1-doc
   */
  @Override
  public synchronized void persist(MessagePair pair) throws Exception {
    if (pair == null) return;
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

  private boolean isHttp(MessageEvent event) {
    return event != null && event.protocol() == ProtocolId.HTTP && event.payload() != null;
  }

  private void writeEvent(MessageEvent event, String kind) throws Exception {
    ByteStream stream = event.payload();
    byte[] data = stream.data();
    int headersEnd = findHeadersEnd(data);
    byte[] headers;
    byte[] body;
    if (headersEnd >= 0) {
      headers = new byte[headersEnd];
      System.arraycopy(data, 0, headers, 0, headersEnd);
      int bodyLen = data.length - headersEnd;
      body = new byte[bodyLen];
      System.arraycopy(data, headersEnd, body, 0, bodyLen);
    } else {
      headers = data;
      body = new byte[0];
    }

    FiveTuple flow = stream.flow();
    String src = endpoint(flow, stream.fromClient());
    String dst = endpoint(flow, !stream.fromClient());
    String id = event.metadata() != null ? event.metadata().transactionId() : null;
    if (id == null) {
      id = TransactionId.newId();
    }
    String session = event.metadata() != null ? event.metadata().attributes().getOrDefault("session", null) : null;

    long headersOff = blob.position();
    blob.write(ByteBuffer.wrap(headers));
    long headersLen = blob.position() - headersOff;

    long bodyOff = blob.position();
    if (body.length > 0) {
      blob.write(ByteBuffer.wrap(body));
    }
    long bodyLen = blob.position() - bodyOff;

    String firstLine = extractFirstLine(headers);
    String json =
        new StringBuilder(256)
            .append('{')
            .append("\"ts_first\":").append(stream.timestampMicros())
            .append(",\"ts_last\":").append(stream.timestampMicros())
            .append(",\"id\":\"").append(id).append('\"')
            .append(",\"kind\":\"").append(kind).append('\"')
            .append(",\"session\":").append(session == null ? "null" : ('\"' + escape(session) + '\"'))
            .append(",\"src\":\"").append(escape(src)).append('\"')
            .append(",\"dst\":\"").append(escape(dst)).append('\"')
            .append(",\"first_line\":\"").append(escape(firstLine)).append('\"')
            .append(",\"headers_blob\":\"").append(blobName).append('\"')
            .append(",\"headers_off\":").append(headersOff)
            .append(",\"headers_len\":").append(headersLen)
            .append(",\"body_blob\":\"").append(blobName).append('\"')
            .append(",\"body_off\":").append(bodyOff)
            .append(",\"body_len\":").append(bodyLen)
            .append('}')
            .toString();

    index.write(json);
    index.newLine();
    index.flush();
    blob.force(false);
  }

  private static int findHeadersEnd(byte[] data) {
    for (int i = 0; i + 3 < data.length; i++) {
      if (data[i] == 13 && data[i + 1] == 10 && data[i + 2] == 13 && data[i + 3] == 10) {
        return i + 4;
      }
    }
    return -1;
  }

  private static String endpoint(FiveTuple flow, boolean fromClient) {
    if (fromClient) {
      return flow.srcIp() + ":" + flow.srcPort();
    }
    return flow.dstIp() + ":" + flow.dstPort();
  }

  private static String extractFirstLine(byte[] headers) {
    int limit = headers.length;
    for (int i = 0; i < limit - 1; i++) {
      if (headers[i] == '\r' && headers[i + 1] == '\n') {
        return new String(headers, 0, i, StandardCharsets.ISO_8859_1).trim();
      }
    }
    return new String(headers, StandardCharsets.ISO_8859_1).trim();
  }

  private static String escape(String value) {
    StringBuilder b = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"', '\\' -> b.append('\\').append(c);
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(' ');
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.toString();
  }

  /**
   * Flushes and closes blob/index streams and the fallback sink.
   *
   * @throws Exception if closing the fallback fails
   * @implNote Forces blob metadata to disk before chaining to the fallback.
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
        blob.force(true);
        blob.close();
        if (fallback != null) {
          fallback.close();
        }
      }
    }
  }
}


