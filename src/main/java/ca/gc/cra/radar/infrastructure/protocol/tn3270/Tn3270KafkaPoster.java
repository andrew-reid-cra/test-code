package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.Tn3270EventSink;
import ca.gc.cra.radar.domain.protocol.tn3270.ScreenField;
import ca.gc.cra.radar.domain.protocol.tn3270.AidKey;
import ca.gc.cra.radar.domain.protocol.tn3270.ScreenSnapshot;
import ca.gc.cra.radar.domain.protocol.tn3270.SessionKey;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Event;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270EventType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka event sink for TN3270 assembler events.
 *
 * @since RADAR 0.2.0
 */
public final class Tn3270KafkaPoster implements Tn3270EventSink {
  private static final Logger log = LoggerFactory.getLogger(Tn3270KafkaPoster.class);
  private static final int SCHEMA_VERSION = 1;
  private static final String PIPELINE_SOURCE = "radar-tn3270-assembler";
  private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

  private final Producer<String, byte[]> producer;
  private final String userActionsTopic;
  private final String screenRenderTopic;
  private final JsonFactory jsonFactory = new JsonFactory();
  private final String hostName;

  /**
   * Creates a poster using the supplied Kafka bootstrap servers.
   *
   * @param bootstrapServers kafka bootstrap servers
   * @param userActionsTopic topic receiving user action events
   * @param screenRenderTopic topic receiving screen render events (may be {@code null} to disable)
   */
  public Tn3270KafkaPoster(String bootstrapServers, String userActionsTopic, String screenRenderTopic) {
    this(createProducer(bootstrapServers), userActionsTopic, screenRenderTopic);
  }

  /**
   * Creates a poster with an explicit Kafka producer (primarily for tests).
   *
   * @param producer kafka producer instance
   * @param userActionsTopic topic receiving user action events
   * @param screenRenderTopic topic receiving screen render events (may be {@code null} to disable)
   */
  public Tn3270KafkaPoster(
      Producer<String, byte[]> producer, String userActionsTopic, String screenRenderTopic) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.userActionsTopic = sanitizeTopic(userActionsTopic);
    this.screenRenderTopic = screenRenderTopic == null ? null : sanitizeTopic(screenRenderTopic);
    this.hostName = resolveHostName();
  }

  @Override
  public void accept(Tn3270Event event) {
    Objects.requireNonNull(event, "event");
    try {
      switch (event.type()) {
        case USER_SUBMIT, SESSION_START, SESSION_END -> publish(userActionsTopic, event);
        case SCREEN_RENDER -> {
          if (screenRenderTopic != null && !screenRenderTopic.isEmpty()) {
            publish(screenRenderTopic, event);
          }
        }
        default -> log.debug("Ignoring TN3270 event type {}", event.type());
      }
    } catch (Exception ex) {
      log.error("Failed to publish TN3270 event {} for session {}", event.type(), event.session(), ex);
    }
  }

  private void publish(String topic, Tn3270Event event) throws Exception {
    byte[] payload = serialize(event);
    ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(topic, event.session().canonical(), payload);
    producer.send(record, (metadata, ex) -> {
      if (ex != null) {
        log.error("Kafka publish failure for topic {} partition {}", topic, metadata == null ? -1 : metadata.partition(), ex);
      }
    });
  }

  private byte[] serialize(Tn3270Event event) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream(512);
    try (JsonGenerator gen = jsonFactory.createGenerator(out)) {
      gen.writeStartObject();
      gen.writeNumberField("schemaVersion", SCHEMA_VERSION);
      gen.writeStringField("eventId", newUlid());
      gen.writeStringField("emittedAt", Instant.now().toString());
      gen.writeStringField("type", event.type().name());
      writeSession(gen, event.session());
      switch (event.type()) {
        case USER_SUBMIT -> writeUserSubmit(gen, event);
        case SCREEN_RENDER -> writeScreenRender(gen, event);
        case SESSION_START, SESSION_END -> writeSessionLifecycle(gen, event);
        default -> {}
      }
      writePipeline(gen);
      gen.writeEndObject();
    }
    return out.toByteArray();
  }

  private void writeSession(JsonGenerator gen, SessionKey session) throws Exception {
    gen.writeObjectFieldStart("session");
    gen.writeStringField("sessionKey", session.canonical());
    gen.writeStringField("sessionId", session.sessionId());
    gen.writeEndObject();
  }

  private void writeUserSubmit(JsonGenerator gen, Tn3270Event event) throws Exception {
    gen.writeObjectFieldStart("tn3270");
    gen.writeStringField("aid", event.aid() == null ? AidKey.UNKNOWN.name() : event.aid().name());
    if (event.screenHash() != null) {
      gen.writeStringField("screenHash", event.screenHash());
    }
    if (event.screenName() != null) {
      gen.writeStringField("screenName", event.screenName());
    }
    gen.writeObjectFieldStart("inputs");
    for (Map.Entry<String, String> entry : event.inputFields().entrySet()) {
      gen.writeStringField(entry.getKey(), entry.getValue());
    }
    gen.writeEndObject();
    gen.writeEndObject();
  }

  private void writeScreenRender(JsonGenerator gen, Tn3270Event event) throws Exception {
    ScreenSnapshot snapshot = event.screen();
    gen.writeObjectFieldStart("tn3270");
    if (snapshot != null) {
      gen.writeNumberField("rows", snapshot.rows());
      gen.writeNumberField("cols", snapshot.cols());
    }
    if (event.screenHash() != null) {
      gen.writeStringField("screenHash", event.screenHash());
    }
    if (event.screenName() != null) {
      gen.writeStringField("screenName", event.screenName());
    }
    gen.writeArrayFieldStart("fields");
    if (snapshot != null) {
      for (ScreenField field : snapshot.fields()) {
        gen.writeStartObject();
        gen.writeNumberField("start", field.start());
        gen.writeNumberField("length", field.length());
        gen.writeBooleanField("protected", field.protectedField());
        gen.writeBooleanField("numeric", field.numeric());
        gen.writeBooleanField("hidden", field.hidden());
        gen.writeStringField("value", field.value());
        gen.writeEndObject();
      }
    }
    gen.writeEndArray();
    gen.writeEndObject();
  }

  private void writeSessionLifecycle(JsonGenerator gen, Tn3270Event event) throws Exception {
    gen.writeObjectFieldStart("tn3270");
    if (event.screenName() != null) {
      gen.writeStringField("screenName", event.screenName());
    }
    gen.writeEndObject();
  }

  private void writePipeline(JsonGenerator gen) throws Exception {
    gen.writeObjectFieldStart("pipeline");
    gen.writeStringField("source", PIPELINE_SOURCE);
    if (hostName != null && !hostName.isBlank()) {
      gen.writeStringField("host", hostName);
    }
    gen.writeEndObject();
  }

  @Override
  public void close() {
    try {
      producer.flush();
    } catch (Exception ex) {
      log.warn("Kafka producer flush failed during shutdown", ex);
    } finally {
      producer.close();
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

  private static String sanitizeTopic(String topic) {
    Objects.requireNonNull(topic, "topic");
    String trimmed = topic.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    return trimmed;
  }

  private static String resolveHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ex) {
      log.warn("Unable to resolve local hostname for pipeline metadata", ex);
      return null;
    }
  }

  private static String newUlid() {
    long time = System.currentTimeMillis();
    byte[] randomness = new byte[10];
    ThreadLocalRandom.current().nextBytes(randomness);
    char[] chars = new char[26];
    chars[0] = CROCKFORD[(int) ((time >> 45) & 0x1F)];
    chars[1] = CROCKFORD[(int) ((time >> 40) & 0x1F)];
    chars[2] = CROCKFORD[(int) ((time >> 35) & 0x1F)];
    chars[3] = CROCKFORD[(int) ((time >> 30) & 0x1F)];
    chars[4] = CROCKFORD[(int) ((time >> 25) & 0x1F)];
    chars[5] = CROCKFORD[(int) ((time >> 20) & 0x1F)];
    chars[6] = CROCKFORD[(int) ((time >> 15) & 0x1F)];
    chars[7] = CROCKFORD[(int) ((time >> 10) & 0x1F)];
    chars[8] = CROCKFORD[(int) ((time >> 5) & 0x1F)];
    chars[9] = CROCKFORD[(int) (time & 0x1F)];

    int r0 = randomness[0] & 0xFF;
    int r1 = randomness[1] & 0xFF;
    int r2 = randomness[2] & 0xFF;
    int r3 = randomness[3] & 0xFF;
    int r4 = randomness[4] & 0xFF;
    int r5 = randomness[5] & 0xFF;
    int r6 = randomness[6] & 0xFF;
    int r7 = randomness[7] & 0xFF;
    int r8 = randomness[8] & 0xFF;
    int r9 = randomness[9] & 0xFF;

    chars[10] = CROCKFORD[(r0 >> 3) & 0x1F];
    chars[11] = CROCKFORD[((r0 & 0x07) << 2 | (r1 >> 6) & 0x03) & 0x1F];
    chars[12] = CROCKFORD[(r1 >> 1) & 0x1F];
    chars[13] = CROCKFORD[((r1 & 0x01) << 4 | (r2 >> 4) & 0x0F) & 0x1F];
    chars[14] = CROCKFORD[((r2 & 0x0F) << 1 | (r3 >> 7) & 0x01) & 0x1F];
    chars[15] = CROCKFORD[(r3 >> 2) & 0x1F];
    chars[16] = CROCKFORD[((r3 & 0x03) << 3 | (r4 >> 5) & 0x07) & 0x1F];
    chars[17] = CROCKFORD[r4 & 0x1F];
    chars[18] = CROCKFORD[(r5 >> 3) & 0x1F];
    chars[19] = CROCKFORD[((r5 & 0x07) << 2 | (r6 >> 6) & 0x03) & 0x1F];
    chars[20] = CROCKFORD[(r6 >> 1) & 0x1F];
    chars[21] = CROCKFORD[((r6 & 0x01) << 4 | (r7 >> 4) & 0x0F) & 0x1F];
    chars[22] = CROCKFORD[((r7 & 0x0F) << 1 | (r8 >> 7) & 0x01) & 0x1F];
    chars[23] = CROCKFORD[(r8 >> 2) & 0x1F];
    chars[24] = CROCKFORD[((r8 & 0x03) << 3 | (r9 >> 5) & 0x07) & 0x1F];
    chars[25] = CROCKFORD[r9 & 0x1F];
    return new String(chars);
  }}


