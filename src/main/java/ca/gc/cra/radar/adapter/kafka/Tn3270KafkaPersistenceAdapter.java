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
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Persists TN3270 message pairs to Kafka topics. */
public final class Tn3270KafkaPersistenceAdapter implements PersistencePort {
  private final Producer<String, byte[]> producer;
  private final String topic;
  private final PersistencePort fallback;

  public Tn3270KafkaPersistenceAdapter(String bootstrapServers, String topic) {
    this(bootstrapServers, topic, null);
  }

  public Tn3270KafkaPersistenceAdapter(
      String bootstrapServers, String topic, PersistencePort fallback) {
    this(createProducer(bootstrapServers), sanitizeTopic(topic), fallback);
  }

  Tn3270KafkaPersistenceAdapter(Producer<String, byte[]> producer, String topic, PersistencePort fallback) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = sanitizeTopic(topic);
    this.fallback = fallback;
  }

  @Override
  public void persist(MessagePair pair) throws Exception {
    if (pair == null) {
      return;
    }
    MessageEvent request = filterTn(pair.request());
    MessageEvent response = filterTn(pair.response());
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

  private static MessageEvent filterTn(MessageEvent event) {
    if (event == null) {
      return null;
    }
    if (event.protocol() != ProtocolId.TN3270 || event.payload() == null) {
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
        .append("\"protocol\":\"TN3270\"")
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
    appendTnMessage(sb, request);
    sb.append(",\"response\":");
    appendTnMessage(sb, response);
    sb.append('}');
    return sb.toString();
  }

  private static void appendTnMessage(StringBuilder sb, MessageEvent event) {
    if (event == null) {
      sb.append("null");
      return;
    }
    ByteStream payload = event.payload();
    byte[] data = payload.data() != null ? payload.data() : new byte[0];
    String body = data.length == 0 ? "" : Base64.getEncoder().encodeToString(data);
    sb.append('{')
        .append("\"timestamp\":").append(payload.timestampMicros())
        .append(",\"direction\":\"")
        .append(event.type() == MessageType.REQUEST ? "REQUEST" : "RESPONSE")
        .append('\"')
        .append(",\"length\":").append(data.length)
        .append(",\"payloadB64\":\"").append(body).append('\"');
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
}
