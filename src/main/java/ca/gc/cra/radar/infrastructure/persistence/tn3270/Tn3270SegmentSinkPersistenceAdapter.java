package ca.gc.cra.radar.infrastructure.persistence.tn3270;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
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

/**
 * Writes TN3270 message events into blob/index files compatible with the HTTP sink.
 * <p>Synchronized to maintain blob/index consistency and optionally delegates non-TN3270 pairs to
 * another {@link PersistencePort}.
 *
 * @since RADAR 0.1-doc
 */
public final class Tn3270SegmentSinkPersistenceAdapter implements PersistencePort {
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final FileChannel blob;
  private final BufferedWriter index;
  private final String blobName;
  private final String indexName;
  private final PersistencePort fallback;

  /**
   * Creates a TN3270 segment sink without a fallback.
   *
   * @param directory output directory for blob/index files
   * @throws NullPointerException if {@code directory} is {@code null}
   * @since RADAR 0.1-doc
   */
  public Tn3270SegmentSinkPersistenceAdapter(Path directory) {
    this(directory, null);
  }

  /**
   * Creates a TN3270 segment sink with an optional fallback.
   *
   * @param directory output directory for blob/index files
   * @param fallback optional persistence fallback for other protocols
   * @throws NullPointerException if {@code directory} is {@code null}
   * @since RADAR 0.1-doc
   */
  public Tn3270SegmentSinkPersistenceAdapter(Path directory, PersistencePort fallback) {
    try {
      Files.createDirectories(directory);
      String stamp = STAMP.format(Instant.now());
      this.blobName = "blob-tn-" + stamp + ".seg";
      this.indexName = "index-tn-" + stamp + ".ndjson";
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
      throw new IllegalStateException("Failed to initialize TN3270 segment sink", e);
    }
  }

  /**
   * Writes TN3270 payloads to disk, delegating other protocols to the fallback.
   *
   * @param pair message pair to persist; {@code null} is ignored
   * @throws Exception if writing fails or the fallback throws
   * @implNote Flushes blob/index metadata each call to mirror historical sink semantics.
   * @since RADAR 0.1-doc
   */
  @Override
  public synchronized void persist(MessagePair pair) throws Exception {
    if (pair == null) {
      return;
    }
    boolean handled = false;
    if (isTn(pair.request())) {
      writeEvent(pair.request(), "REQ");
      handled = true;
    }
    if (isTn(pair.response())) {
      writeEvent(pair.response(), "RSP");
      handled = true;
    }
    if (!handled && fallback != null) {
      fallback.persist(pair);
    }
  }

  private boolean isTn(MessageEvent event) {
    return event != null && event.protocol() == ProtocolId.TN3270 && event.payload() != null;
  }

  private void writeEvent(MessageEvent event, String kind) throws Exception {
    ByteStream stream = event.payload();
    byte[] data = stream.data();
    FiveTuple flow = stream.flow();
    String src = endpoint(flow, stream.fromClient());
    String dst = endpoint(flow, !stream.fromClient());

    String id = event.metadata() != null ? event.metadata().transactionId() : null;
    if (id == null || id.isBlank()) {
      id = TransactionId.newId();
    }

    long bodyOff = blob.position();
    if (data.length > 0) {
      blob.write(ByteBuffer.wrap(data));
    }
    long bodyLen = blob.position() - bodyOff;

    String firstLine = preview(data);
    String json =
        new StringBuilder(256)
            .append('{')
            .append("\"ts_first\":").append(stream.timestampMicros())
            .append(",\"ts_last\":").append(stream.timestampMicros())
            .append(",\"id\":\"").append(escape(id)).append('\"')
            .append(",\"kind\":\"").append(kind).append('\"')
            .append(",\"src\":\"").append(escape(src)).append('\"')
            .append(",\"dst\":\"").append(escape(dst)).append('\"')
            .append(",\"first_line\":\"").append(escape(firstLine)).append('\"')
            .append(",\"headers_blob\":\"").append(blobName).append('\"')
            .append(",\"headers_off\":").append(bodyOff)
            .append(",\"headers_len\":0")
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

  private static String endpoint(FiveTuple flow, boolean fromClient) {
    return fromClient ? flow.srcIp() + ":" + flow.srcPort() : flow.dstIp() + ":" + flow.dstPort();
  }

  private static String preview(byte[] data) {
    if (data == null || data.length == 0) {
      return "TN3270";
    }
    int len = Math.min(data.length, 16);
    StringBuilder sb = new StringBuilder(len * 2);
    for (int i = 0; i < len; i++) {
      int b = data[i] & 0xFF;
      sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    if (data.length > len) {
      sb.append("...");
    }
    return sb.toString();
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
   * @implNote Forces blob metadata to disk before invoking the fallback close.
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

