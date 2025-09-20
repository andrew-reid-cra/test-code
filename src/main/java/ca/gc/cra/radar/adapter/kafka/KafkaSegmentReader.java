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

/** Kafka consumer that streams {@link SegmentRecord} instances from a topic. */
public final class KafkaSegmentReader implements AutoCloseable {
  private final Consumer<String, byte[]> consumer;

  public KafkaSegmentReader(String bootstrapServers, String topic) {
    this(createConsumer(bootstrapServers), topic);
  }

  KafkaSegmentReader(Consumer<String, byte[]> consumer, String topic) {
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    String sanitizedTopic = sanitizeTopic(topic);
    this.consumer.subscribe(java.util.List.of(sanitizedTopic));
  }

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


