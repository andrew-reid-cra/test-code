package ca.gc.cra.radar.infrastructure.persistence.tn3270;

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

/**
 * Writes TN3270 message events into blob/index files compatible with the HTTP sink.
 * <p>Synchronized to maintain blob/index consistency and optionally delegates non-TN3270 pairs to
 * another {@link PersistencePort}.</p>
 */
public final class Tn3270SegmentSinkPersistenceAdapter implements PersistencePort {
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final BlobWriter blob;
  private final BufferedWriter index;
  private final String blobName;
  private final String indexName;
  private final PersistencePort fallback;

  /**
   * Creates a TN3270 sink that writes blobs and indices under the given directory.
   *
   * @param directory destination directory for TN3270 artifacts
   */
  public Tn3270SegmentSinkPersistenceAdapter(Path directory) {
    this(directory, null);
  }

  /**
   * Creates a TN3270 sink that optionally forwards non-TN pairs to a fallback port.
   *
   * @param directory destination directory for TN3270 artifacts
   * @param fallback delegate used for non-TN3270 message pairs (nullable)
   */
  public Tn3270SegmentSinkPersistenceAdapter(Path directory, PersistencePort fallback) {
    try {
      Files.createDirectories(directory);
      String stamp = STAMP.format(Instant.now());
      this.blobName = "blob-tn-" + stamp + ".seg";
      this.indexName = "index-tn-" + stamp + ".ndjson";
      this.blob = new BlobWriter(directory.resolve(blobName));
      this.index = Files.newBufferedWriter(
          directory.resolve(indexName),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      this.fallback = fallback;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to initialize TN3270 segment sink", e);
    }
  }

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

  private static boolean isTn(MessageEvent event) {
    return event != null && event.protocol() == ProtocolId.TN3270 && event.payload() != null;
  }

  private void writeEvent(MessageEvent event, String kind) throws Exception {
    ByteStream stream = event.payload();
    byte[] data = stream.data();
    FiveTuple flow = stream.flow();
    String src = endpoint(flow, stream.fromClient());
    String dst = endpoint(flow, !stream.fromClient());

    MessageMetadata metadata = event.metadata() != null ? event.metadata() : MessageMetadata.empty();
    String transactionId = metadata.transactionId() != null && !metadata.transactionId().isBlank()
        ? metadata.transactionId()
        : TransactionId.newId();

    long bodyOffset = blob.position();
    if (data.length > 0) {
      blob.write(data, 0, data.length);
    }
    long bodyLength = blob.position() - bodyOffset;

    String firstLine = preview(data);

    StringBuilder json = new StringBuilder(160)
        .append('{')
        .append("\"ts_first\":").append(stream.timestampMicros()).append(',')
        .append("\"ts_last\":").append(stream.timestampMicros()).append(',')
        .append("\"id\":\"").append(escape(transactionId)).append("\",")
        .append("\"kind\":\"").append(kind).append("\",")
        .append("\"src\":\"").append(escape(src)).append("\",")
        .append("\"dst\":\"").append(escape(dst)).append("\",")
        .append("\"first_line\":\"").append(firstLine).append("\",")
        .append("\"headers_blob\":\"").append(blobName).append("\",")
        .append("\"headers_off\":").append(bodyOffset).append(',')
        .append("\"headers_len\":0,")
        .append("\"body_blob\":\"").append(blobName).append("\",")
        .append("\"body_off\":").append(bodyOffset).append(',')
        .append("\"body_len\":").append(bodyLength)
        .append('}');

    index.write(json.toString());
    index.newLine();
    index.flush();
    blob.flush(false);
  }

  private static String endpoint(FiveTuple flow, boolean fromClient) {
    return fromClient ? flow.srcIp() + ':' + flow.srcPort() : flow.dstIp() + ':' + flow.dstPort();
  }

  private static String preview(byte[] data) {
    if (data == null || data.length == 0) {
      return "";
    }
    int len = Math.min(data.length, 16);
    StringBuilder sb = new StringBuilder(len * 2 + (data.length > len ? 3 : 0));
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
