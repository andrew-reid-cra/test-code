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
 * <strong>What:</strong> Kafka-backed {@link SegmentPersistencePort} that streams serialized TCP segments.
 * <p><strong>Why:</strong> Lets the capture stage hand off segments to assemble pipelines running on other hosts.</p>
 * <p><strong>Role:</strong> Infrastructure adapter on the sink side (capture -> assemble hand-off).</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Serialize {@link SegmentRecord} instances and publish them to Kafka.</li>
 *   <li>Preserve per-flow ordering via keyed producer records.</li>
 *   <li>Flush and close the producer gracefully during shutdown.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Mirrors the provided {@link Producer}; the default {@link KafkaProducer} is thread-safe.</p>
 * <p><strong>Performance:</strong> Relies on Kafka batching; serialization is linear in payload size.</p>
 * <p><strong>Observability:</strong> Emits Kafka client metrics; callers should layer {@code sink.kafka.*} counters around usage.</p>
 *
 * @since 0.1.0
 */
public final class SegmentKafkaSinkAdapter implements SegmentPersistencePort {
  private final Producer<String, byte[]> producer;
  private final String topic;

  /**
   * Creates a Kafka sink for segment records.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers; must not be blank
   * @param topic Kafka topic that receives serialized segments; must not be blank
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if any parameter is blank
   *
   * <p><strong>Concurrency:</strong> Construct on a single configuration thread.</p>
   * <p><strong>Performance:</strong> Initializes a {@link KafkaProducer} with batching-friendly defaults.</p>
   * <p><strong>Observability:</strong> Producer exposes standard Kafka metrics; callers should log bootstrap/topic details.</p>
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
   *
   * <p><strong>Concurrency:</strong> Safe when the underlying {@link Producer} is thread-safe.</p>
   * <p><strong>Performance:</strong> Serialization is linear in payload size; producer batches as configured.</p>
   * <p><strong>Observability:</strong> Callers should increment {@code sink.kafka.segments} counters and monitor producer metrics.</p>
   * @implNote Uses a flow-based key to preserve per-connection ordering.
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
   * <p><strong>Concurrency:</strong> Safe for concurrent calls; delegates to the producer.</p>
   * <p><strong>Performance:</strong> Blocks until in-flight batches are acknowledged.</p>
   * <p><strong>Observability:</strong> Callers may record flush latency when draining pipelines.</p>
   */
  @Override
  public void flush() {
    producer.flush();
  }

  /**
   * Flushes pending messages and closes the producer.
   *
   * <p><strong>Concurrency:</strong> Invoke once during shutdown after all persist calls have completed.</p>
   * <p><strong>Performance:</strong> Flushes and closes the producer, waiting up to five seconds for in-flight sends.</p>
   * <p><strong>Observability:</strong> Callers should log closure and monitor Kafka client metrics for errors.</p>
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

