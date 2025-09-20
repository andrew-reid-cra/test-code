package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/** Publishes formatted poster reports to Kafka. */
public final class KafkaPosterOutputAdapter implements PosterOutputPort {
  private final Producer<String, String> producer;
  private final String topic;

  public KafkaPosterOutputAdapter(String bootstrapServers, String topic) {
    this(createProducer(bootstrapServers), sanitizeTopic(topic));
  }

  KafkaPosterOutputAdapter(Producer<String, String> producer, String topic) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = sanitizeTopic(topic);
  }

  @Override
  public void write(PosterReport report) {
    Objects.requireNonNull(report, "report");
    producer.send(new ProducerRecord<>(topic, report.txId(), report.content()));
  }

  @Override
  public void close() {
    producer.flush();
    producer.close(Duration.ofSeconds(5));
  }

  private static Producer<String, String> createProducer(String bootstrapServers) {
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
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    return new KafkaProducer<>(props);
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
