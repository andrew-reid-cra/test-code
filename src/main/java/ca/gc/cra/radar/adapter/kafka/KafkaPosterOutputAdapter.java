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

/**
 * Kafka poster adapter that publishes formatted {@link PosterReport} payloads to a Kafka topic.
 * <p>Acts as the infrastructure adapter for {@link ca.gc.cra.radar.application.port.poster.PosterOutputPort}
 * and delegates publishing to an underlying {@link Producer}. Instances are thread-safe when the
 * supplied producer is thread-safe (the default {@link KafkaProducer} is).
 *
 * @implNote Records are enqueued asynchronously; invoke {@link #close()} to flush buffers before
 * shutting down the pipeline.
 * @see ca.gc.cra.radar.application.port.poster.PosterOutputPort
 * @since RADAR 0.1-doc
 */
public final class KafkaPosterOutputAdapter implements PosterOutputPort {
  private final Producer<String, String> producer;
  private final String topic;

  /**
   * Creates a poster output adapter backed by a new {@link KafkaProducer}.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers
   * @param topic Kafka topic that receives poster reports
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if {@code bootstrapServers} is blank or {@code topic} is {@code null} or blank
   * @since RADAR 0.1-doc
   */
  public KafkaPosterOutputAdapter(String bootstrapServers, String topic) {
    this(createProducer(bootstrapServers), sanitizeTopic(topic));
  }

  KafkaPosterOutputAdapter(Producer<String, String> producer, String topic) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = sanitizeTopic(topic);
  }

  /**
   * Enqueues a {@link PosterReport} for asynchronous publication to Kafka.
   *
   * @param report poster report to publish; must not be {@code null}
   * @throws NullPointerException if {@code report} is {@code null}
   * @implNote Uses {@link PosterReport#txId()} as the record key and {@link PosterReport#content()}
   *     as the payload without additional mutation.
   * @since RADAR 0.1-doc
   */
  @Override
  public void write(PosterReport report) {
    Objects.requireNonNull(report, "report");
    producer.send(new ProducerRecord<>(topic, report.txId(), report.content()));
  }

  /**
   * Flushes pending Kafka records and closes the producer.
   *
   * @implNote Waits up to five seconds for in-flight send operations to complete.
   * @since RADAR 0.1-doc
   */
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
