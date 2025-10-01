package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 * @since RADAR 0.1-doc
 */
public final class Tn3270KafkaPosterPipeline implements PosterPipeline {
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
    this.consumerSupplier = consumerSupplier != null ? consumerSupplier : this::createConsumer;
  }

  /**
   * Identifies the protocol handled by this pipeline.
   *
   * @return {@link ProtocolId#TN3270}
   * @since RADAR 0.1-doc
   */
  @Override
  public ProtocolId protocol() {
    return ProtocolId.TN3270;
  }

  /**
   * Streams Kafka records and emits formatted TN3270 poster reports via the supplied output port.
   *
   * @param config per-protocol configuration; must provide the Kafka input topic
   * @param decodeMode decode mode controlling payload processing
   * @param outputPort destination for rendered poster reports
   * @throws NullPointerException if {@code config} or {@code outputPort} is {@code null}
   * @throws Exception if Kafka consumption or report rendering fails
   * @implNote Polls Kafka in 500 ms intervals and stops after several idle polls or interrupt.
   * @since RADAR 0.1-doc
   */
  @Override
  public void process(
      PosterConfig.ProtocolConfig config,
      PosterConfig.DecodeMode decodeMode,
      PosterOutputPort outputPort) throws Exception {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(outputPort, "outputPort");
    String topic = config.kafkaInputTopic()
        .orElseThrow(() -> new IllegalArgumentException("tn kafka input topic required"));

    try (Consumer<String, String> consumer = consumerSupplier.get()) {
      consumer.subscribe(List.of(topic));
      int idlePolls = 0;
      final int maxIdlePolls = 10;
      while (!Thread.currentThread().isInterrupted()) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        if (records.isEmpty()) {
          if (++idlePolls >= maxIdlePolls) {
            break;
          }
        } else {
          idlePolls = 0;
          for (ConsumerRecord<String, String> kafkaRecord : records) {
            TnPair pair = parsePair(kafkaRecord.value());
            if (pair == null) {
              continue;
            }
            String content = formatPair(pair);
            long ts = pair.startTs > 0 ? pair.startTs : pair.endTs;
            outputPort.write(new PosterReport(ProtocolId.TN3270, pair.txId, ts, content));
          }
        }
      }
    }
  }

  private Consumer<String, String> createConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "radar-poster-tn-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    return new KafkaConsumer<>(props);
  }

  private static TnPair parsePair(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      String txId = extractString(json, "txId");
      long startTs = extractLong(json, "startTs", 0L);
      long endTs = extractLong(json, "endTs", 0L);
      Endpoint client = parseEndpoint(extractObject(json, "client"));
      Endpoint server = parseEndpoint(extractObject(json, "server"));
      BinaryMessage request = parseBinaryMessage(extractObject(json, "request"));
      BinaryMessage response = parseBinaryMessage(extractObject(json, "response"));
      return new TnPair(txId, startTs, endTs, client, server, request, response);
    } catch (Exception ex) {
      return null;
    }
  }

  private static BinaryMessage parseBinaryMessage(String json) {
    if (json == null || json.isBlank() || json.equals("null")) {
      return null;
    }
    long timestamp = extractLong(json, "timestamp", 0L);
    int length = (int) extractLong(json, "length", 0L);
    String bodyB64 = extractString(json, "payloadB64");
    byte[] payload = bodyB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(bodyB64);
    Map<String, String> attrs = parseAttributes(extractObject(json, "attributes"));
    return new BinaryMessage(timestamp, length, payload, attrs);
  }

  private static Map<String, String> parseAttributes(String json) {
    Map<String, String> map = new LinkedHashMap<>();
    if (json == null || json.length() < 2) {
      return map;
    }
    int idx = 0;
    while (idx < json.length()) {
      int keyStart = json.indexOf('"', idx);
      if (keyStart < 0) {
        return map;
      }
      int keyEnd = findStringEnd(json, keyStart + 1);
      if (keyEnd < 0) {
        return map;
      }
      String key = unescape(json.substring(keyStart + 1, keyEnd));
      int colon = json.indexOf(':', keyEnd);
      if (colon < 0) {
        return map;
      }
      int valueStart = json.indexOf('"', colon);
      if (valueStart < 0) {
        return map;
      }
      int valueEnd = findStringEnd(json, valueStart + 1);
      if (valueEnd < 0) {
        return map;
      }
      String value = unescape(json.substring(valueStart + 1, valueEnd));
      map.put(key, value);
      idx = valueEnd + 1;
    }
    return map;
  }

  private static Endpoint parseEndpoint(String json) {
    if (json == null || json.isBlank()) {
      return new Endpoint("0.0.0.0", 0);
    }
    String ip = extractString(json, "ip");
    int port = (int) extractLong(json, "port", 0L);
    return new Endpoint(ip, port);
  }

  private static String formatPair(TnPair pair) {
    StringBuilder sb = new StringBuilder(1024);
    sb.append("=== TN3270 REQUEST ===\n");
    if (pair.request != null) {
      appendBinarySection(sb, pair.client, pair.server, true, pair.request);
    } else {
      sb.append("(no request captured)\n");
    }
    sb.append('\n');
    sb.append("=== TN3270 RESPONSE ===\n");
    if (pair.response != null) {
      appendBinarySection(sb, pair.client, pair.server, false, pair.response);
    } else {
      sb.append("(no response captured)\n");
    }
    return sb.toString();
  }

  private static void appendBinarySection(
      StringBuilder sb,
      Endpoint client,
      Endpoint server,
      boolean request,
      BinaryMessage message) {
    Endpoint src = request ? client : server;
    Endpoint dst = request ? server : client;
    sb.append("# timestamp_us: ").append(message.timestamp).append('\n');
    sb.append("# src: ").append(src.ip).append(':').append(src.port).append('\n');
    sb.append("# dst: ").append(dst.ip).append(':').append(dst.port).append('\n');
    if (!message.attributes.isEmpty()) {
      sb.append("# attrs: ");
      boolean first = true;
      for (Map.Entry<String, String> entry : message.attributes.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        sb.append(entry.getKey()).append('=').append(entry.getValue());
        first = false;
      }
      sb.append('\n');
    }
    sb.append('\n');
    if (message.payload.length == 0) {
      sb.append("(empty payload)\n");
    } else {
      sb.append(hexDump(message.payload));
    }
  }

  private static String extractObject(String json, String key) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      return null;
    }
    int braceStart = json.indexOf('{', idx + token.length());
    if (braceStart < 0) {
      int nullStart = json.indexOf("null", idx + token.length());
      if (nullStart == idx + token.length()) {
        return "null";
      }
      return null;
    }
    int depth = 0;
    for (int i = braceStart; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return json.substring(braceStart, i + 1);
        }
      }
    }
    return null;
  }

  private static String extractString(String json, String key) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      return "";
    }
    int startQuote = json.indexOf('"', idx + token.length());
    if (startQuote < 0) {
      return "";
    }
    int endQuote = findStringEnd(json, startQuote + 1);
    if (endQuote < 0) {
      return "";
    }
    return unescape(json.substring(startQuote + 1, endQuote));
  }

  private static long extractLong(String json, String key, long defaultValue) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      return defaultValue;
    }
    int start = idx + token.length();
    int end = start;
    while (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) {
      end++;
    }
    if (end == start) {
      return defaultValue;
    }
    try {
      return Long.parseLong(json.substring(start, end));
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static int findStringEnd(String json, int start) {
    boolean escaping = false;
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaping) {
        escaping = false;
      } else if (c == '\\') {
        escaping = true;
      } else if (c == '"') {
        return i;
      }
    }
    return -1;
  }

  private static String unescape(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    boolean escaping = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (escaping) {
        switch (c) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          default -> sb.append(c);
        }
        escaping = false;
      } else if (c == '\\') {
        escaping = true;
      } else {
        sb.append(c);
      }
    }
    if (escaping) {
      sb.append('\\');
    }
    return sb.toString();
  }

  private static String hexDump(byte[] data) {
    StringBuilder sb = new StringBuilder(data.length * 3);
    for (int i = 0; i < data.length; i++) {
      if (i > 0 && (i % 16) == 0) {
        sb.append('\n');
      }
      int b = data[i] & 0xFF;
      sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
      sb.append(' ');
    }
    return sb.toString().trim();
  }

  private record Endpoint(String ip, int port) {}

  private record BinaryMessage(long timestamp, int length, byte[] payload, Map<String, String> attributes) {
    BinaryMessage {
      payload = payload != null ? payload : new byte[0];
      attributes = attributes != null ? attributes : Map.of();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof BinaryMessage(long otherTimestamp, int otherLength, byte[] otherPayload, Map<String, String> otherAttributes))) {
        return false;
      }
      return timestamp == otherTimestamp
          && length == otherLength
          && Arrays.equals(payload, otherPayload)
          && Objects.equals(attributes, otherAttributes);
    }

    @Override
    public int hashCode() {
      int result = Long.hashCode(timestamp);
      result = 31 * result + Integer.hashCode(length);
      result = 31 * result + Arrays.hashCode(payload);
      result = 31 * result + Objects.hashCode(attributes);
      return result;
    }

    @Override
    public String toString() {
      return "BinaryMessage{"
          + "timestamp=" + timestamp
          + ", length=" + length
          + ", payload=" + Arrays.toString(payload)
          + ", attributes=" + attributes
          + '}';
    }
  }

  private record TnPair(
      String txId,
      long startTs,
      long endTs,
      Endpoint client,
      Endpoint server,
      BinaryMessage request,
      BinaryMessage response) {}
}









