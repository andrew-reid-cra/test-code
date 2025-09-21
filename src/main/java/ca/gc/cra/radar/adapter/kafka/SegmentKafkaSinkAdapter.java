package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Kafka-backed {@link SegmentPersistencePort} that streams serialized TCP segments to a topic.
 * <p>Acts as an infrastructure adapter for the assemble pipeline. Thread-safety follows the
 * supplied {@link Producer} implementation (the default {@link KafkaProducer} is thread-safe).
 *
 * @since RADAR 0.1-doc
 */
public final class SegmentKafkaSinkAdapter implements SegmentPersistencePort {
  private final Producer<String, byte[]> producer;
  private final String topic;

  /**
   * Creates a Kafka sink for segment records.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers
   * @param topic topic that receives serialized segments
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if {@code bootstrapServers} is blank or {@code topic} is {@code null} or blank
   * @since RADAR 0.1-doc
   */
  public SegmentKafkaSinkAdapter(String bootstrapServers, String topic) {
    this(createProducer(bootstrapServers), sanitizeTopic(topic));
  }

  SegmentKafkaSinkAdapter(Producer<String, byte[]> producer, String topic) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = sanitizeTopic(topic);
  }

  /**
   * Publishes the segment to Kafka.
   *
   * @param record segment record to persist; {@code null} inputs are ignored
   * @implNote Uses a flow-based key to preserve per-connection ordering.
   * @since RADAR 0.1-doc
   */
  @Override
  public void persist(SegmentRecord record) {
    if (record == null) {
      return;
    }
    ProducerRecord<String, byte[]> message =
        new ProducerRecord<>(topic, flowKey(record), serialize(record));
    producer.send(message);
  }

  /**
   * Flushes outstanding Kafka sends.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public void flush() {
    producer.flush();
  }

  /**
   * Flushes pending messages and closes the producer.
   *
   * @implNote Waits up to five seconds for in-flight sends to complete.
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() {
    producer.flush();
    producer.close(Duration.ofSeconds(5));
  }

  private static String flowKey(SegmentRecord record) {
    return new StringBuilder(64)
        .append(record.srcIp()).append(':').append(record.srcPort())
        .append('>').append(record.dstIp()).append(':').append(record.dstPort())
        .toString();
  }

  private static byte[] serialize(SegmentRecord record) {
    String payloadB64 = Base64.getEncoder().encodeToString(record.payload());
    String json =
        new StringBuilder(256)
            .append('{')
            .append("\"ts\":").append(record.timestampMicros())
            .append(",\"src\":\"").append(escape(record.srcIp())).append('\"')
            .append(",\"sport\":").append(record.srcPort())
            .append(",\"dst\":\"").append(escape(record.dstIp())).append('\"')
            .append(",\"dport\":").append(record.dstPort())
            .append(",\"seq\":").append(record.sequence())
            .append(",\"flags\":").append(record.flags())
            .append(",\"payload\":\"").append(payloadB64).append('\"')
            .append('}')
            .toString();
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\');
      }
      sb.append(c);
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
}

