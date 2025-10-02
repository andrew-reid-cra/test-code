package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.time.Duration;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Kafka-backed poster pipeline that consumes TN3270 message pairs and renders poster reports.
 * <p>Implements the infrastructure side of {@link PosterPipeline} for TN3270. Instances are not
 * thread-safe; run one pipeline per CLI execution.
 *
 * <p>Refactoring goals:
 * <ul>
 *   <li>Reduce duplicated code in JSON parsing and section formatting.</li>
 *   <li>Keep behavior identical and fast; avoid additional dependencies.</li>
 * </ul>
 *
 * @since RADAR 0.1-doc
 */
public final class Tn3270KafkaPosterPipeline implements PosterPipeline {

  private static final Logger log = LoggerFactory.getLogger(Tn3270KafkaPosterPipeline.class);

  // ---- Kafka & poll constants (centralized to avoid scattering magic numbers) ----
  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
  private static final int MAX_IDLE_POLLS = 10;
  private static final String GROUP_ID_PREFIX = "radar-poster-tn-";

  private final String bootstrapServers;
  private final Supplier<Consumer<String, String>> consumerSupplier;

  /**
   * Creates a poster pipeline that consumes TN3270 pair records from Kafka.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if {@code bootstrapServers} is blank
   * @since RADAR 0.1-doc
   */
  public Tn3270KafkaPosterPipeline(String bootstrapServers) {
    this(bootstrapServers, null);
  }

  Tn3270KafkaPosterPipeline(String bootstrapServers, Supplier<Consumer<String, String>> consumerSupplier) {
    this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers").trim();
    if (this.bootstrapServers.isEmpty()) {
      throw new IllegalArgumentException("bootstrapServers must not be blank");
    }
    this.consumerSupplier = (consumerSupplier != null) ? consumerSupplier : this::createConsumer;
  }

  /**
   * Identifies the protocol handled by this pipeline.
   */
  @Override
  public ProtocolId protocol() {
    return ProtocolId.TN3270;
  }

  /**
   * Streams Kafka records and emits formatted TN3270 poster reports via the supplied output port.
   *
   * @param config per-protocol configuration; must provide the Kafka input topic
   * @param decodeMode decode mode controlling payload processing (currently passthrough)
   * @param outputPort destination for rendered poster reports
   * @throws NullPointerException if {@code config} or {@code outputPort} is {@code null}
   * @throws Exception if Kafka consumption or report rendering fails
   * @implNote Polls Kafka at a fixed cadence and exits after several idle polls or interrupt.
   */
  @Override
  public void process(
      PosterConfig.ProtocolConfig config,
      PosterConfig.DecodeMode decodeMode,
      PosterOutputPort outputPort) throws Exception {

    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(outputPort, "outputPort");

    final String topic = config.kafkaInputTopic()
        .orElseThrow(() -> new IllegalArgumentException("tn kafka input topic required"));

    try (Consumer<String, String> consumer = consumerSupplier.get()) {
      consumer.subscribe(List.of(topic));

      int idlePolls = 0;
      while (!Thread.currentThread().isInterrupted()) {
        ConsumerRecords<String, String> records = consumer.poll(POLL_INTERVAL);

        if (records.isEmpty()) {
          if (++idlePolls >= MAX_IDLE_POLLS) break;
          continue;
        }

        idlePolls = 0;
        for (ConsumerRecord<String, String> rec : records) {
          TnPair pair = parsePair(rec.value());
          if (pair == null) continue;

          final String content = formatPair(pair);
          final long ts = (pair.startTs > 0) ? pair.startTs : pair.endTs;
          outputPort.write(new PosterReport(ProtocolId.TN3270, pair.txId, ts, content));
        }
      }
    }
  }

  // ------------------------------------------------------------------------------------
  // Kafka consumer factory
  // ------------------------------------------------------------------------------------

  private Consumer<String, String> createConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID_PREFIX + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    return new KafkaConsumer<>(props);
  }

  // ------------------------------------------------------------------------------------
  // Parse TN3270 pair (fast, no external JSON dependency)
  // ------------------------------------------------------------------------------------

  private static TnPair parsePair(String json) {
    if (isBlank(json)) return null;

    try {
      String txId = Json.getString(json, "txId", "");
      long startTs = Json.getLong(json, "startTs", 0L);
      long endTs = Json.getLong(json, "endTs", 0L);

      Endpoint client = parseEndpoint(Json.getObject(json, "client"));
      Endpoint server = parseEndpoint(Json.getObject(json, "server"));

      BinaryMessage request = parseBinaryMessage(Json.getObject(json, "request"));
      BinaryMessage response = parseBinaryMessage(Json.getObject(json, "response"));

      return new TnPair(txId, startTs, endTs, client, server, request, response);
    } catch (RuntimeException ex) {
      return PosterParseUtils.logParseFailure(log, "TN3270", ex);
    }
  }

  private static BinaryMessage parseBinaryMessage(String json) {
    if (isBlank(json) || "null".equals(json)) return null;

    long timestamp = Json.getLong(json, "timestamp", 0L);
    int length = (int) Json.getLong(json, "length", 0L);
    String bodyB64 = Json.getString(json, "payloadB64", "");
    byte[] payload = bodyB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(bodyB64);
    Map<String, String> attrs = parseAttributes(Json.getObject(json, "attributes"));

    return new BinaryMessage(timestamp, length, payload, attrs);
  }

  private static Map<String, String> parseAttributes(String json) {
    // Attributes is a flat string map: { "k":"v", ... }
    Map<String, String> map = new LinkedHashMap<>();
    if (json == null || json.length() < 2) return map;

    int idx = 0;
    final int n = json.length();
    while (idx < n) {
      int keyStart = json.indexOf('"', idx);
      if (keyStart < 0) break;

      int keyEnd = Json.findStringEnd(json, keyStart + 1);
      if (keyEnd < 0) break;
