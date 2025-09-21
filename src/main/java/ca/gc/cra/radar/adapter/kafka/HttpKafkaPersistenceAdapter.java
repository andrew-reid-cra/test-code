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
 * Kafka persistence adapter that serializes HTTP {@link MessagePair} instances into JSON and
 * publishes them to Kafka.
 * <p>Implements the infrastructure side of {@link PersistencePort} for HTTP data. Non-HTTP pairs
 * can be delegated to an optional fallback adapter. Thread-safety follows the supplied
 * {@link Producer} implementation (the default {@link KafkaProducer} is thread-safe).
 *
 * @implNote HTTP bodies are base64 encoded and headers preserved verbatim using ISO-8859-1 to
 * avoid charset loss.
 * @see PersistencePort
 * @since RADAR 0.1-doc
 */
public final class HttpKafkaPersistenceAdapter implements PersistencePort {
  private final Producer<String, byte[]> producer;
  private final String topic;
  private final PersistencePort fallback;

  /**
   * Builds an HTTP persistence adapter backed by a new {@link KafkaProducer}.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers
   * @param topic Kafka topic that receives serialized HTTP exchanges
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if {@code bootstrapServers} is blank or {@code topic} is {@code null} or blank
   * @since RADAR 0.1-doc
   */
  public HttpKafkaPersistenceAdapter(String bootstrapServers, String topic) {
    this(bootstrapServers, topic, null);
  }

  /**
   * Builds an adapter that can delegate non-HTTP traffic to the supplied fallback.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers
   * @param topic Kafka topic that receives serialized HTTP exchanges
   * @param fallback persistence adapter that receives non-HTTP message pairs; may be {@code null}
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if {@code bootstrapServers} is blank or {@code topic} is {@code null} or blank
   * @since RADAR 0.1-doc
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
   * Persists the HTTP-specific components of the supplied pair to Kafka, delegating non-HTTP pairs
   * to the configured fallback.
   *
   * @param pair message pair to persist; {@code null} inputs are ignored
   * @throws Exception if publishing fails or the fallback raises an exception
   * @implNote Uses {@link TransactionId} metadata when available; otherwise generates a new id.
   * @since RADAR 0.1-doc
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
   * @throws Exception if the fallback fails while closing
   * @implNote Attempts a bounded flush (five seconds) before closing the producer.
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() throws Exception {
    try {
      producer.flush();
      producer.close(Duration.ofSeconds(5));
    } finally {
      if (fallback != null) {
        fallback.close();
      }
    }
  }

  private static MessageEvent filterHttp(MessageEvent event) {
    if (event == null) {
      return null;
    }
    if (event.protocol() != ProtocolId.HTTP || event.payload() == null) {
      return null;
    }
    return event;
  }

  private static String resolveTxId(MessageEvent request, MessageEvent response) {
    String id = metadataId(request);
    if (id == null || id.isBlank()) {
      id = metadataId(response);
    }
    if (id == null || id.isBlank()) {
      id = TransactionId.newId();
    }
    return id;
  }

  private static String metadataId(MessageEvent event) {
    if (event == null) {
      return null;
    }
    MessageMetadata metadata = event.metadata();
    return metadata == null ? null : metadata.transactionId();
  }

  private static Endpoints resolveEndpoints(MessageEvent request, MessageEvent response) {
    if (request != null && request.payload() != null) {
      return toEndpoints(request.payload(), request.payload().fromClient());
    }
    if (response != null && response.payload() != null) {
      return toEndpoints(response.payload(), !response.payload().fromClient());
    }
    return new Endpoints("0.0.0.0", 0, "0.0.0.0", 0);
  }

  private static Endpoints toEndpoints(ByteStream stream, boolean fromClient) {
    FiveTuple flow = stream.flow();
    if (fromClient) {
      return new Endpoints(flow.srcIp(), flow.srcPort(), flow.dstIp(), flow.dstPort());
    }
    return new Endpoints(flow.dstIp(), flow.dstPort(), flow.srcIp(), flow.srcPort());
  }

  private static String buildPayload(
      String txId,
      long startTs,
      long endTs,
      Endpoints endpoints,
      MessageEvent request,
      MessageEvent response) {
    StringBuilder sb = new StringBuilder(1024);
    sb.append('{')
        .append("\"protocol\":\"HTTP\"")
        .append(",\"txId\":\"").append(escape(txId)).append('\"')
        .append(",\"startTs\":").append(startTs)
        .append(",\"endTs\":").append(endTs)
        .append(",\"client\":{")
        .append("\"ip\":\"").append(escape(endpoints.clientIp())).append('\"')
        .append(",\"port\":").append(endpoints.clientPort())
        .append('}')
        .append(",\"server\":{")
        .append("\"ip\":\"").append(escape(endpoints.serverIp())).append('\"')
        .append(",\"port\":").append(endpoints.serverPort())
        .append('}')
        .append(",\"request\":");
    appendHttpMessage(sb, request);
    sb.append(",\"response\":");
    appendHttpMessage(sb, response);
    sb.append('}');
    return sb.toString();
  }

  private static void appendHttpMessage(StringBuilder sb, MessageEvent event) {
    if (event == null) {
      sb.append("null");
      return;
    }
    ByteStream payload = event.payload();
    HttpParts parts = HttpParts.from(payload.data());
    String firstLine = parts.firstLine();
    int status = event.type() == MessageType.RESPONSE ? parseStatus(firstLine) : 0;
    sb.append('{')
        .append("\"timestamp\":").append(payload.timestampMicros())
        .append(",\"firstLine\":\"").append(escape(firstLine)).append('\"')
        .append(",\"headers\":\"").append(escape(parts.headers())).append('\"')
        .append(",\"bodyB64\":\"").append(parts.bodyB64()).append('\"');
    if (status > 0) {
      sb.append(",\"status\":").append(status);
    }
    appendAttributes(sb, event.metadata());
    sb.append('}');
  }

  private static void appendAttributes(StringBuilder sb, MessageMetadata metadata) {
    Map<String, String> attributes = metadata != null ? metadata.attributes() : Map.of();
    boolean any = false;
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || value == null) {
        continue;
      }
      if (!any) {
        sb.append(",\"attributes\":{");
        any = true;
      } else {
        sb.append(',');
      }
      sb.append('"').append(escape(key)).append('\"')
          .append(':')
          .append('"').append(escape(value)).append('"');
    }
    if (any) {
      sb.append('}');
    }
  }

  private static int parseStatus(String firstLine) {
    if (firstLine == null || firstLine.isBlank()) {
      return 0;
    }
    StringTokenizer tokenizer = new StringTokenizer(firstLine, " ");
    if (!tokenizer.hasMoreTokens()) {
      return 0;
    }
    tokenizer.nextToken();
    if (!tokenizer.hasMoreTokens()) {
      return 0;
    }
    String maybeStatus = tokenizer.nextToken();
    try {
      return Integer.parseInt(maybeStatus);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\', '"' -> sb.append('\\').append(c);
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String sanitizeTopic(String topic) {
    if (topic == null) {
      throw new IllegalArgumentException("topic must not be null");
    }
    String trimmed = topic.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    return trimmed;
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
