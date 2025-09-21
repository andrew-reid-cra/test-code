package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Kafka-backed poster pipeline that consumes serialized HTTP message pairs and renders poster
 * reports.
 * <p>Implements the infrastructure side of {@link PosterPipeline} for HTTP, building reports from
 * JSON records produced by {@link HttpKafkaPersistenceAdapter}. Instances are not thread-safe;
 * create one pipeline per running CLI.
 *
 * @since RADAR 0.1-doc
 */
public final class HttpKafkaPosterPipeline implements PosterPipeline {
  private final String bootstrapServers;
  private final Supplier<Consumer<String, String>> consumerSupplier;

  /**
   * Creates a poster pipeline that consumes HTTP pair records from Kafka.
   *
   * @param bootstrapServers comma-separated Kafka bootstrap servers
   * @throws NullPointerException if {@code bootstrapServers} is {@code null}
   * @throws IllegalArgumentException if {@code bootstrapServers} is blank
   * @since RADAR 0.1-doc
   */
  public HttpKafkaPosterPipeline(String bootstrapServers) {
    this(bootstrapServers, null);
  }

  HttpKafkaPosterPipeline(String bootstrapServers, Supplier<Consumer<String, String>> consumerSupplier) {
    this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers").trim();
    if (this.bootstrapServers.isEmpty()) {
      throw new IllegalArgumentException("bootstrapServers must not be blank");
    }
    this.consumerSupplier = consumerSupplier != null ? consumerSupplier : this::createConsumer;
  }

  /**
   * Identifies the protocol handled by this pipeline.
   *
   * @return {@link ProtocolId#HTTP}
   * @since RADAR 0.1-doc
   */
  @Override
  public ProtocolId protocol() {
    return ProtocolId.HTTP;
  }

  /**
   * Streams Kafka records and emits formatted poster reports through the supplied output port.
   *
   * @param config per-protocol configuration; must provide the Kafka input topic
   * @param decodeMode requested decode behavior for HTTP payloads
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
        .orElseThrow(() -> new IllegalArgumentException("http kafka input topic required"));

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
          continue;
        }
        idlePolls = 0;
        for (ConsumerRecord<String, String> record : records) {
          HttpPair pair = parsePair(record.value());
          if (pair == null) {
            continue;
          }
          String content = formatPair(pair, decodeMode);
          long ts = pair.startTs > 0 ? pair.startTs : pair.endTs;
          PosterReport report = new PosterReport(ProtocolId.HTTP, pair.txId, ts, content);
          outputPort.write(report);
        }
      }
    }
  }

  private Consumer<String, String> createConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "radar-poster-http-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    return new KafkaConsumer<>(props);
  }

  private static HttpPair parsePair(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      String txId = extractString(json, "txId");
      long startTs = extractLong(json, "startTs", 0L);
      long endTs = extractLong(json, "endTs", 0L);
      Endpoint client = parseEndpoint(extractObject(json, "client"));
      Endpoint server = parseEndpoint(extractObject(json, "server"));
      HttpMessage request = parseMessage(extractObject(json, "request"));
      HttpMessage response = parseMessage(extractObject(json, "response"));
      return new HttpPair(txId, startTs, endTs, client, server, request, response);
    } catch (Exception ex) {
      return null;
    }
  }

  private static Endpoint parseEndpoint(String json) {
    if (json == null || json.isBlank()) {
      return new Endpoint("0.0.0.0", 0);
    }
    String ip = extractString(json, "ip");
    int port = (int) extractLong(json, "port", 0L);
    return new Endpoint(ip, port);
  }

  private static HttpMessage parseMessage(String json) {
    if (json == null || json.isBlank() || json.equals("null")) {
      return null;
    }
    long timestamp = extractLong(json, "timestamp", 0L);
    String firstLine = extractString(json, "firstLine");
    String headers = extractString(json, "headers");
    String bodyB64 = extractString(json, "bodyB64");
    byte[] body = bodyB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(bodyB64);
    int status = (int) extractLong(json, "status", 0L);
    Map<String, String> attributes = parseAttributes(extractObject(json, "attributes"));
    return new HttpMessage(timestamp, firstLine, headers, body, status, attributes);
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
        break;
      }
      int keyEnd = findStringEnd(json, keyStart + 1);
      if (keyEnd < 0) {
        break;
      }
      String key = unescape(json.substring(keyStart + 1, keyEnd));
      int colon = json.indexOf(':', keyEnd);
      if (colon < 0) {
        break;
      }
      int valueStart = json.indexOf('"', colon);
      if (valueStart < 0) {
        break;
      }
      int valueEnd = findStringEnd(json, valueStart + 1);
      if (valueEnd < 0) {
        break;
      }
      String value = unescape(json.substring(valueStart + 1, valueEnd));
      map.put(key, value);
      idx = valueEnd + 1;
    }
    return map;
  }

  private static int findStringEnd(String json, int start) {
    boolean escaping = false;
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaping) {
        escaping = false;
        continue;
      }
      if (c == '\\') {
        escaping = true;
        continue;
      }
      if (c == '"') {
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

  private static String formatPair(HttpPair pair, PosterConfig.DecodeMode decodeMode) {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("=== HTTP REQUEST ===\n");
    if (pair.request != null) {
      appendHttpSection(sb, pair.txId, pair.client, pair.server, true, pair.request, decodeMode);
    } else {
      sb.append("(no request captured)\n");
    }
    sb.append('\n');
    sb.append("=== HTTP RESPONSE ===\n");
    if (pair.response != null) {
      appendHttpSection(sb, pair.txId, pair.client, pair.server, false, pair.response, decodeMode);
    } else {
      sb.append("(no response captured)\n");
    }
    return sb.toString();
  }

  private static void appendHttpSection(
      StringBuilder sb,
      String txId,
      Endpoint client,
      Endpoint server,
      boolean request,
      HttpMessage message,
      PosterConfig.DecodeMode decodeMode) {
    if (message == null) {
      sb.append("# ").append(request ? "request" : "response").append(" missing\n\n");
      return;
    }
    HttpMessage decoded = decodeHttpMessage(message, decodeMode);
    Endpoint src = request ? client : server;
    Endpoint dst = request ? server : client;
    sb.append("# id: ").append(txId).append('\n');
    sb.append("# timestamp_us: ").append(decoded.timestamp()).append('\n');
    sb.append("# src: ").append(src.ip()).append(':').append(src.port()).append('\n');
    sb.append("# dst: ").append(dst.ip()).append(':').append(dst.port()).append('\n');
    sb.append('\n');
    if (decoded.firstLine() != null && !decoded.firstLine().isBlank()) {
      sb.append(decoded.firstLine()).append('\n');
    }
    String headers = decoded.headers();
    if (headers != null && !headers.isBlank()) {
      for (String line : headers.split("\r?\n")) {
        if (line.isEmpty()) {
          continue;
        }
        sb.append(line).append('\n');
      }
    }
    if (decoded.status() > 0) {
      sb.append("# status: ").append(decoded.status()).append('\n');
    }
    Map<String, String> attrs = decoded.attributes();
    if (attrs != null && !attrs.isEmpty()) {
      StringJoiner joiner = new StringJoiner(", ");
      attrs.forEach((k, v) -> joiner.add(k + '=' + v));
      sb.append("# attrs: ").append(joiner.toString()).append('\n');
    }
    sb.append('\n');
    appendBody(sb, decoded.body());
  }  private static HttpMessage decodeHttpMessage(HttpMessage message, PosterConfig.DecodeMode mode) {
    if (message == null || message.body().length == 0) {
      return message;
    }
    byte[] originalBody = message.body();
    byte[] workingBody = originalBody;
    Map<String, String> headerMap = new LinkedHashMap<>(headerMap(message.headers()));
    if (mode.decodeTransferEncoding()) {
      String transfer = headerMap.getOrDefault("Transfer-Encoding", "");
      if (!transfer.isBlank() && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
        try {
          workingBody = decodeChunked(workingBody);
          headerMap.remove("Transfer-Encoding");
          headerMap.put("Content-Length", Integer.toString(workingBody.length));
        } catch (Exception ignore) {
          workingBody = originalBody;
        }
      }
    }
    if (mode.decodeContentEncoding()) {
      String encoding = headerMap.getOrDefault("Content-Encoding", "");
      String lower = encoding.toLowerCase(Locale.ROOT);
      try {
        if (lower.contains("gzip")) {
          workingBody = decodeGzip(workingBody);
          headerMap.remove("Content-Encoding");
          headerMap.put("Content-Length", Integer.toString(workingBody.length));
        } else if (lower.contains("deflate")) {
          workingBody = decodeDeflate(workingBody);
          headerMap.remove("Content-Encoding");
          headerMap.put("Content-Length", Integer.toString(workingBody.length));
        }
      } catch (Exception ignore) {
        workingBody = originalBody;
      }
    }
    String rebuiltHeaders = headerMap.isEmpty() ? message.headers() : rebuildHeaders(headerMap);
    return new HttpMessage(
        message.timestamp(),
        message.firstLine(),
        rebuiltHeaders,
        workingBody,
        message.status(),
        message.attributes());
  }  private static Map<String, String> headerMap(String headers) {
    Map<String, String> map = new LinkedHashMap<>();
    if (headers == null) {
      return map;
    }
    for (String line : headers.split("\r?\n")) {
      int colon = line.indexOf(':');
      if (colon > 0) {
        String name = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        if (!name.isEmpty()) {
          map.put(name, value);
        }
      }
    }
    return map;
  }

  private static String rebuildHeaders(Map<String, String> headers) {
    if (headers.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    headers.forEach((k, v) -> sb.append(k).append(':').append(' ').append(v).append('\n'));
    return sb.toString().trim();
  }

  private static void appendBody(StringBuilder sb, byte[] body) {
    if (body.length == 0) {
      sb.append("(empty body)\n");
      return;
    }
    if (isLikelyText(body)) {
      String text = new String(body, StandardCharsets.ISO_8859_1);
      sb.append(text);
      if (!endsWithNewline(text)) {
        sb.append('\n');
      }
    } else {
      sb.append("[binary payload ").append(body.length).append(" bytes]\n");
      sb.append(hexDump(body));
    }
  }

  private static byte[] decodeChunked(byte[] body) throws IOException {
    ByteArrayInputStream in = new ByteArrayInputStream(body);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      String line = readLine(in);
      if (line == null) {
        break;
      }
      int size = Integer.parseInt(line.trim(), 16);
      if (size == 0) {
        break;
      }
      byte[] chunk = in.readNBytes(size);
      out.write(chunk);
      // consume CRLF
      in.read();
      in.read();
    }
    return out.toByteArray();
  }

  private static String readLine(ByteArrayInputStream in) {
    StringBuilder sb = new StringBuilder();
    int b;
    while ((b = in.read()) >= 0) {
      if (b == '\r') {
        int next = in.read();
        if (next == '\n') {
          break;
        }
      }
      sb.append((char) b);
    }
    if (sb.isEmpty()) {
      return null;
    }
    return sb.toString();
  }

  private static byte[] decodeGzip(byte[] data) throws IOException {
    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return gis.readAllBytes();
    }
  }

  private static byte[] decodeDeflate(byte[] data) throws IOException {
    try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data))) {
      return iis.readAllBytes();
    }
  }

  private static boolean isLikelyText(byte[] data) {
    int limit = Math.min(data.length, 64);
    for (int i = 0; i < limit; i++) {
      int b = data[i] & 0xFF;
      if (b == 0) {
        return false;
      }
      if (b < 0x09 || (b > 0x0D && b < 0x20)) {
        return false;
      }
    }
    return true;
  }

  private static boolean endsWithNewline(String text) {
    return text.endsWith("\n") || text.endsWith("\r");
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

  private record HttpMessage(
      long timestamp,
      String firstLine,
      String headers,
      byte[] body,
      int status,
      Map<String, String> attributes) {
    HttpMessage {
      body = body != null ? body : new byte[0];
      attributes = attributes != null ? attributes : Map.of();
    }
  }

  private record HttpPair(
      String txId,
      long startTs,
      long endTs,
      Endpoint client,
      Endpoint server,
      HttpMessage request,
      HttpMessage response) {}
}







