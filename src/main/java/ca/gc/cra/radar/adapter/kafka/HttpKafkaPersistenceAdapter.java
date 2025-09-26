package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.msg.TransactionId;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.StringTokenizer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * <strong>What:</strong> Kafka persistence adapter that serializes HTTP {@link MessagePair} instances to JSON.
 * <p><strong>Why:</strong> Provides a sink-side adapter so reconstructed HTTP exchanges reach Kafka topics for downstream analytics.</p>
 * <p><strong>Role:</strong> Adapter implementing {@link PersistencePort} on the sink side.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Filter incoming pairs to HTTP-only content.</li>
 *   <li>Serialize headers and bodies with base64-safe encoding.</li>
 *   <li>Publish records to Kafka or delegate non-HTTP payloads to a fallback.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Delegates to the provided {@link Producer}; the default {@link KafkaProducer} is thread-safe.</p>
 * <p><strong>Performance:</strong> Reuses a single producer instance, batches using linger settings, and avoids extra allocations.</p>
 * <p><strong>Observability:</strong> Emits Kafka client metrics and logs publish failures; callers should also record {@code sink.kafka.*} counters.</p>
 *
 * @implNote HTTP bodies are base64 encoded while headers remain ISO-8859-1 to prevent charset loss.
 * @since 0.1.0
 * @see PersistencePort
 */
public final class HttpKafkaPersistenceAdapter implements PersistencePort {
  private final Producer<String, byte[]> producer;
  private final String topic;
  private final PersistencePort fallback;

  /**
   * Builds an HTTP persistence adapter backed by a new {@link KafkaProducer}.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers; must be non-null/non-blank
   * @param topic Kafka topic that receives serialized HTTP exchanges; must be non-null/non-blank
   *
   * <p><strong>Concurrency:</strong> Safe to construct on a single configuration thread.</p>
   * <p><strong>Performance:</strong> Allocates a producer configured for high-throughput publishing.</p>
   * <p><strong>Observability:</strong> Producer exposes standard Kafka client metrics.</p>
   */
  public HttpKafkaPersistenceAdapter(String bootstrapServers, String topic) {
    this(bootstrapServers, topic, null);
  }

  /**
   * Builds an adapter that can delegate non-HTTP traffic to the supplied fallback.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers; must be non-null/non-blank
   * @param topic Kafka topic that receives serialized HTTP exchanges; must be non-null/non-blank
   * @param fallback persistence adapter that receives non-HTTP message pairs; may be {@code null}
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread; resulting instance is safe for multi-threaded use consistent with the producer.</p>
   * <p><strong>Performance:</strong> Creates a single producer and stores the delegate reference.</p>
   * <p><strong>Observability:</strong> Logs topic and bootstrap configuration at debug level when integrated.</p>
   */
  public HttpKafkaPersistenceAdapter(
      String bootstrapServers, String topic, PersistencePort fallback) {
    this(createProducer(bootstrapServers), sanitizeTopic(topic), fallback);
  }

  HttpKafkaPersistenceAdapter(Producer<String, byte[]> producer, String topic, PersistencePort fallback) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = sanitizeTopic(topic);
    this.fallback = fallback;
  }

  /**
   * Persists HTTP-specific components of the supplied pair to Kafka, delegating non-HTTP pairs to the fallback.
   *
   * @param pair message pair to persist; {@code null} inputs are ignored
   * @throws Exception if publishing fails or the fallback raises an exception
   *
   * <p><strong>Concurrency:</strong> Safe to invoke from multiple threads when the producer is thread-safe.</p>
   * <p><strong>Performance:</strong> Uses asynchronous producer sends; serialization is linear in payload size.</p>
   * <p><strong>Observability:</strong> Callers should track {@code sink.kafka.persisted} and {@code sink.kafka.fallback} counters.</p>
   */
  @Override
  public void persist(MessagePair pair) throws Exception {
    if (pair == null) {
      return;
    }
    MessageEvent request = filterHttp(pair.request());
    MessageEvent response = filterHttp(pair.response());
    if (request == null && response == null) {
      if (fallback != null) {
        fallback.persist(pair);
      }
      return;
    }

    String txId = resolveTxId(request, response);
    Endpoints endpoints = resolveEndpoints(request, response);
    long startTs = Long.MAX_VALUE;
    long endTs = Long.MIN_VALUE;
    if (request != null && request.payload() != null) {
      long ts = request.payload().timestampMicros();
      startTs = Math.min(startTs, ts);
      endTs = Math.max(endTs, ts);
    }
    if (response != null && response.payload() != null) {
      long ts = response.payload().timestampMicros();
      startTs = Math.min(startTs, ts);
      endTs = Math.max(endTs, ts);
    }
    if (startTs == Long.MAX_VALUE) {
      startTs = endTs = 0L;
    }

    String json = buildPayload(txId, startTs, endTs, endpoints, request, response);
    producer.send(new ProducerRecord<>(topic, txId, json.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Flushes Kafka buffers and closes both this adapter and the optional fallback.
   *
   * @throws Exception if the producer close fails or the fallback throws an exception
   *
   * <p><strong>Concurrency:</strong> Call once during shutdown after all persist operations complete.</p>
   * <p><strong>Performance:</strong> Blocks until the producer flushes in-flight records.</p>
   * <p><strong>Observability:</strong> Implementations should log shutdown and emit {@code sink.kafka.closed} metrics.</p>
   */
  @Override
  public void close() throws Exception {
    try {
      producer.flush();
    } finally {
      producer.close(Duration.ofSeconds(5));
      if (fallback != null) {
        fallback.close();
      }
    }
  }

  private static Producer<String, byte[]> createProducer(String bootstrapServers) {
    Objects.requireNonNull(bootstrapServers, "bootstrapServers");
    String trimmed = bootstrapServers.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("bootstrapServers must not be blank");
    }
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, trimmed);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    return new KafkaProducer<>(props);
  }

  private record Endpoints(String clientIp, int clientPort, String serverIp, int serverPort) {}

  private record HttpParts(String headers, String firstLine, String bodyB64) {
    static HttpParts from(byte[] data) {
      if (data == null) {
        return new HttpParts("", "", "");
      }
      int boundary = findBoundary(data);
      byte[] headerBytes;
      byte[] bodyBytes;
      if (boundary >= 0) {
        headerBytes = new byte[boundary];
        System.arraycopy(data, 0, headerBytes, 0, boundary);
        int bodyLen = data.length - boundary;
        bodyBytes = new byte[bodyLen];
        System.arraycopy(data, boundary, bodyBytes, 0, bodyLen);
      } else {
        headerBytes = data;
        bodyBytes = new byte[0];
      }
      String headers = new String(headerBytes, StandardCharsets.ISO_8859_1).trim();
      String firstLine = extractFirstLine(headers);
      String bodyEncoded = bodyBytes.length == 0 ? "" : Base64.getEncoder().encodeToString(bodyBytes);
      return new HttpParts(headers, firstLine, bodyEncoded);
    }

    private static int findBoundary(byte[] data) {
      for (int i = 0; i + 3 < data.length; i++) {
        if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
          return i + 4;
        }
      }
      return -1;
    }

    private static String extractFirstLine(String headers) {
      if (headers == null || headers.isEmpty()) {
        return "";
      }
      int idx = headers.indexOf('\n');
      if (idx < 0) {
        return headers.strip();
      }
      return headers.substring(0, idx).strip();
    }
  }
}



