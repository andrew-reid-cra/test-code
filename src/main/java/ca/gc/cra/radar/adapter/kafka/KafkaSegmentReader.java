package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * <strong>What:</strong> Kafka consumer wrapper that streams {@link SegmentRecord} instances.
 * <p><strong>Why:</strong> Feeds assemble pipelines from capture topics when running in Kafka mode.</p>
 * <p><strong>Role:</strong> Infrastructure adapter bridging capture output topics to assemble use cases.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Subscribe to a segment topic and deserialize records.</li>
 *   <li>Provide polling semantics that align with assemble processing.</li>
 *   <li>Close the consumer cleanly during shutdown.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Not thread-safe; one consumer per reader.</p>
 * <p><strong>Performance:</strong> Relies on Kafka client batching; decoding is linear in payload size.</p>
 * <p><strong>Observability:</strong> Exposes Kafka client metrics; callers should layer {@code assemble.kafka.*} counters.</p>
 *
 * @since 0.1.0
 */
public final class KafkaSegmentReader implements AutoCloseable {
  private final Consumer<String, byte[]> consumer;

  /**
   * Creates a segment reader bound to the specified Kafka topic.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers; must not be blank
   * @param topic Kafka topic containing serialized segment records; must not be blank
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if any parameter is blank
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread.</p>
   * <p><strong>Performance:</strong> Initializes a {@link KafkaConsumer} with sensible defaults.</p>
   * <p><strong>Observability:</strong> Consumer exposes standard Kafka metrics; callers should log topic group details.</p>
   */
  public KafkaSegmentReader(String bootstrapServers, String topic) {
    this(createConsumer(bootstrapServers), topic);
  }

  KafkaSegmentReader(Consumer<String, byte[]> consumer, String topic) {
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    String sanitizedTopic = sanitizeTopic(topic);
    this.consumer.subscribe(java.util.List.of(sanitizedTopic));
  }

  /**
   * Polls Kafka for the next segment record.
   *
   * @param timeout maximum time to wait for a record; must not be {@code null}
   * @return next segment record when available; otherwise {@link Optional#empty()}
   * @throws NullPointerException if {@code timeout} is {@code null}
   *
   * <p><strong>Concurrency:</strong> Call from a single consumer thread.</p>
   * <p><strong>Performance:</strong> Delegates to {@link Consumer#poll(Duration)} and decodes Base64 payloads; latency driven by broker configuration.</p>
   * <p><strong>Observability:</strong> Callers should meter lag and deserialize errors (malformed records are skipped).</p>
   */
  public Optional<SegmentRecord> poll(Duration timeout) {
    ConsumerRecords<String, byte[]> records = consumer.poll(timeout);
    Iterator<ConsumerRecord<String, byte[]>> iterator = records.iterator();
    while (iterator.hasNext()) {
      ConsumerRecord<String, byte[]> record = iterator.next();
      try {
        return Optional.of(deserialize(record.value()));
      } catch (IllegalArgumentException ex) {
        // Skip malformed payloads but continue consuming.
      }
    }
    return Optional.empty();
  }

  /**
   * Closes the underlying Kafka consumer after flushing outstanding commits.
   *
   * <p><strong>Concurrency:</strong> Invoke once when tearing down the reader.</p>
   * <p><strong>Performance:</strong> Waits up to five seconds for the consumer to close cleanly.</p>
   * <p><strong>Observability:</strong> Callers should log shutdown and monitor Kafka client metrics for errors.</p>
   */
  @Override
  public void close() {
    consumer.close(Duration.ofSeconds(5));
  }

  private static Consumer<String, byte[]> createConsumer(String bootstrapServers) {
    Objects.requireNonNull(bootstrapServers, "bootstrapServers");
    String trimmed = bootstrapServers.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("bootstrapServers must not be blank");
    }
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, trimmed);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "radar-assemble-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
    return new KafkaConsumer<>(props);
  }

  private static SegmentRecord deserialize(byte[] payload) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    String json = new String(payload, StandardCharsets.UTF_8);
    long ts = extractLong(json, "ts");
    String src = extractString(json, "src");
    int sport = (int) extractLong(json, "sport");
    String dst = extractString(json, "dst");
    int dport = (int) extractLong(json, "dport");
    long seq = extractLong(json, "seq");
    int flags = (int) extractLong(json, "flags");
    String payloadB64 = extractString(json, "payload");
    byte[] data = payloadB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(payloadB64);
    return new SegmentRecord(ts, src, sport, dst, dport, seq, flags, data);
  }

  private static long extractLong(String json, String key) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      throw new IllegalArgumentException("Missing numeric field " + key);
    }
    int start = idx + token.length();
    int end = start;
    while (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) {
      end++;
    }
    if (end == start) {
      throw new IllegalArgumentException("Empty numeric field " + key);
    }
    return Long.parseLong(json.substring(start, end));
  }

  private static String extractString(String json, String key) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      throw new IllegalArgumentException("Missing string field " + key);
    }
    int startQuote = json.indexOf('"', idx + token.length());
    if (startQuote < 0) {
      throw new IllegalArgumentException("Malformed string field " + key);
    }
    StringBuilder sb = new StringBuilder();
    boolean escaping = false;
    for (int i = startQuote + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaping) {
        sb.append(c);
        escaping = false;
        continue;
      }
      if (c == '\\') {
        escaping = true;
        continue;
      }
      if (c == '"') {
        break;
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
}



