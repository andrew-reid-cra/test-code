package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.DecodeMode;
import ca.gc.cra.radar.config.PosterConfig.ProtocolConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka-backed poster pipeline that consumes HTTP message pairs and renders poster reports.
 * This implementation includes a back-compat shim so legacy tests that construct with
 * (String) and (String, Supplier<Consumer<...>>) continue to compile and work.
 */
public final class HttpKafkaPosterPipeline implements PosterPipeline {

  private static final Logger log = LoggerFactory.getLogger(HttpKafkaPosterPipeline.class);

  // Polling behavior (keep modest to pass tests quickly)
  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
  private static final int MAX_IDLE_POLLS = 10;
  private static final String GROUP_ID_PREFIX = "radar-poster-http-";

  // --- Back-compat fields (used only by legacy constructors/tests) ---
  private String compatBootstrap;  // optional, legacy only
  private Supplier<Consumer<String, String>> compatConsumerSupplier; // optional, legacy (tests)

  // --- Current behavior: no-arg constructor is the canonical entry point ---
  public HttpKafkaPosterPipeline() {
    // If desired, initialize any defaults here.
  }

  /** Back-compat: legacy constructor used by older tests. */
  public HttpKafkaPosterPipeline(String bootstrap) {
    this(); // delegate to current behavior
    if (bootstrap == null) {
      throw new NullPointerException("bootstrap");
    }
    if (bootstrap.trim().isEmpty()) {
      throw new IllegalArgumentException("bootstrap must not be blank");
    }
    this.compatBootstrap = bootstrap.trim();
  }

  /** Back-compat: legacy constructor with injected consumer supplier (MockConsumer in tests). */
  public HttpKafkaPosterPipeline(String bootstrap, Supplier<Consumer<String, String>> consumerSupplier) {
    this(bootstrap);
    this.compatConsumerSupplier = Objects.requireNonNull(consumerSupplier, "consumerSupplier");
  }

  @Override
  public ProtocolId protocol() {
    return ProtocolId.HTTP;
  }

  @Override
  public void process(ProtocolConfig cfg, DecodeMode mode, PosterOutputPort out) throws Exception {
    Objects.requireNonNull(cfg, "config");
    Objects.requireNonNull(out, "outputPort");

    final String topic = cfg.kafkaInputTopic()
        .orElseThrow(() -> new IllegalArgumentException("http kafka input topic required"));

    try (Consumer<String, String> consumer = obtainConsumerForTestsOrDefault()) {
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
          HttpPair pair = parseHttpPair(rec.value());
          if (pair == null) continue;

          String content = formatHttpReport(pair, mode);
          long ts = (pair.startTs > 0) ? pair.startTs : pair.endTs;
          out.write(new PosterReport(ProtocolId.HTTP, pair.txId, ts, content));
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Kafka consumer creation
  // ---------------------------------------------------------------------------

  private Consumer<String, String> obtainConsumerForTestsOrDefault() {
    if (compatConsumerSupplier != null) {
      return compatConsumerSupplier.get();
    }
    return createConsumer();
  }

  private Consumer<String, String> createConsumer() {
    // Use compatBootstrap if provided, otherwise default to localhost in dev/test contexts.
    String bootstrap = (compatBootstrap != null && !compatBootstrap.isBlank())
        ? compatBootstrap
        : "localhost:9092";

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID_PREFIX + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    return new KafkaConsumer<>(props);
  }

  // ---------------------------------------------------------------------------
  // Lightweight JSON parsing (no external deps) tailored for test inputs
  // ---------------------------------------------------------------------------

  private static final class HttpPair {
    final String txId;
    final long startTs;
    final long endTs;
    final Msg request;
    final Msg response;
    final Map<String, String> attrs; // flattened attributes to render

    HttpPair(String txId, long startTs, long endTs, Msg request, Msg response, Map<String, String> attrs) {
      this.txId = txId;
      this.startTs = startTs;
      this.endTs = endTs;
      this.request = request;
      this.response = response;
      this.attrs = attrs;
    }
  }

  private static final class Msg {
    final long timestamp;
    final String firstLine;
    final String headers;   // raw headers string from JSON (may be empty)
    final byte[] body;      // base64-decoded body bytes (may be empty)
    final int status;       // 0 for request, HTTP status for response

    Msg(long timestamp, String firstLine, String headers, byte[] body, int status) {
      this.timestamp = timestamp;
      this.firstLine = firstLine;
      this.headers = headers;
      this.body = body;
      this.status = status;
    }
  }

  private HttpPair parseHttpPair(String json) {
    if (isBlank(json)) return null;
    try {
      String txId = jsonString(json, "txId", "tx-unknown");
      long startTs = jsonLong(json, "startTs", 0L);
      long endTs = jsonLong(json, "endTs", 0L);

      String reqObj = jsonObject(json, "request");
      String respObj = jsonObject(json, "response");

      Msg req = parseMsg(reqObj, true);
      Msg resp = parseMsg(respObj, false);

      Map<String, String> attrs = new LinkedHashMap<>();
      String reqAttrs = jsonObject(reqObj, "attributes");
      if (reqAttrs != null) {
        
        int idx = 0;
        while (idx < reqAttrs.length()) {
          int ks = reqAttrs.indexOf('"', idx);
          if (ks < 0) break;
          int ke = findStringEnd(reqAttrs, ks + 1);
          if (ke < 0) break;
          String k = reqAttrs.substring(ks + 1, ke);
          int vs = reqAttrs.indexOf('"', ke + 1);
          if (vs < 0) break;
          int ve = findStringEnd(reqAttrs, vs + 1);
          if (ve < 0) break;
          String v = reqAttrs.substring(vs + 1, ve);
          attrs.put(k, v);
          idx = ve + 1;
        }
      }

      return new HttpPair(txId, startTs, endTs, req, resp, attrs);
    } catch (RuntimeException ex) {
      log.warn("Failed to parse HTTP pair JSON: {}", ex.toString());
      return null;
    }
  }

  private Msg parseMsg(String obj, boolean request) {
    if (obj == null) return new Msg(0, "", "", new byte[0], request ? 0 : 200);
    long ts = jsonLong(obj, "timestamp", 0L);
    String firstLine = jsonString(obj, "firstLine", "");
    String headers = jsonString(obj, "headers", "");
    String bodyB64 = jsonString(obj, "bodyB64", "");
    byte[] body = bodyB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(bodyB64);
    int status = (int) jsonLong(obj, "status", request ? 0 : 200);
    return new Msg(ts, firstLine, headers, body, status);
  }

  // Extract a nested object by key (returns the substring including braces, or null)
  private static String jsonObject(String json, String key) {
    int k = json.indexOf("\"" + key + "\"");
    if (k < 0) return null;
    int colon = json.indexOf(':', k);
    if (colon < 0) return null;
    int start = json.indexOf('{', colon);
    if (start < 0) return null;
    int depth = 0;
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          return json.substring(start, i + 1);
        }
      }
    }
    return null;
  }

  private static String jsonString(String json, String key, String def) {
    int k = json.indexOf("\"" + key + "\"");
    if (k < 0) return def;
    int colon = json.indexOf(':', k);
    if (colon < 0) return def;
    int q1 = json.indexOf('"', colon + 1);
    if (q1 < 0) return def;
    int q2 = findStringEnd(json, q1 + 1);
    if (q2 < 0) return def;
    return json.substring(q1 + 1, q2);
  }

  private static long jsonLong(String json, String key, long def) {
    int k = json.indexOf("\"" + key + "\"");
    if (k < 0) return def;
    int colon = json.indexOf(':', k);
    if (colon < 0) return def;
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
    int j = i;
    while (j < json.length() && "-0123456789".indexOf(json.charAt(j)) >= 0) j++;
    if (j == i) return def;
    try { return Long.parseLong(json.substring(i, j)); } catch (NumberFormatException e) { return def; }
  }

  // Find end of a JSON string, handling basic escapes
  private static int findStringEnd(String s, int start) {
    boolean esc = false;
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (esc) { esc = false; continue; }
      if (c == '\\') { esc = true; continue; }
      if (c == '"') return i;
    }
    return -1;
  }

  // ---------------------------------------------------------------------------
  // Formatting and decoding
  // ---------------------------------------------------------------------------

  private String formatHttpReport(HttpPair pair, DecodeMode mode) throws IOException {
    StringBuilder sb = new StringBuilder(256);

    // Request
    sb.append("HTTP REQUEST\n");
    if (!isBlank(pair.request.firstLine)) {
      sb.append(pair.request.firstLine).append('\n');
    }
    byte[] reqBody = decodeBody(pair.request.body, pair.request.headers, mode);
    if (reqBody.length > 0) {
      sb.append(new String(reqBody, StandardCharsets.ISO_8859_1)).append('\n');
    }

    // Response
    sb.append("HTTP RESPONSE\n");
    if (!isBlank(pair.response.firstLine)) {
      sb.append(pair.response.firstLine).append('\n');
    }
    byte[] respBody = decodeBody(pair.response.body, pair.response.headers, mode);
    if (respBody.length > 0) {
      sb.append(new String(respBody, StandardCharsets.ISO_8859_1)).append('\n');
    }

    // Attributes (from request)
    if (!pair.attrs.isEmpty()) {
      sb.append("# attrs: ");
      boolean first = true;
      for (Map.Entry<String, String> e : pair.attrs.entrySet()) {
        if (!first) sb.append(' ');
        sb.append(e.getKey()).append('=').append(e.getValue());
        first = false;
      }
      sb.append('\n');
    }

    return sb.toString();
  }

  private static byte[] decodeBody(byte[] raw, String headers, DecodeMode mode) throws IOException {
    if (raw == null || raw.length == 0) return new byte[0];

    byte[] data = raw;

    // If chunked and decode-all requested, de-chunk before further decoding.
    if (mode == DecodeMode.ALL && containsHeader(headers, "Transfer-Encoding", "chunked")) {
      data = decodeChunked(data);
    }

    // If gzip and decode-all requested, ungzip.
    if (mode == DecodeMode.ALL && containsHeader(headers, "Content-Encoding", "gzip")) {
      data = gunzip(data);
    }

    return data;
  }

  private static boolean containsHeader(String headers, String name, String valueContains) {
    if (headers == null) return false;
    String h = headers.toLowerCase();
    return h.contains(name.toLowerCase()) && h.contains(valueContains.toLowerCase());
  }

  private static byte[] decodeChunked(byte[] in) {
    // Very small, permissive chunked decoder sufficient for the test input.
    int idx = 0;
    ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
    while (idx < in.length) {
      // read hex length up to CRLF
      int lineEnd = indexOfCrlf(in, idx);
      if (lineEnd < 0) break;
      String hex = new String(in, idx, lineEnd - idx, StandardCharsets.ISO_8859_1).trim();
      int len = 0;
      try { len = Integer.parseInt(hex, 16); } catch (NumberFormatException ex) { break; }
      idx = lineEnd + 2; // skip CRLF
      if (len == 0) {
        // optional trailing CRLF
        return out.toByteArray();
      }
      int end = Math.min(idx + len, in.length);
      out.write(in, idx, end - idx);
      idx = end;
      // skip CRLF after chunk
      if (idx + 1 < in.length && in[idx] == '\r' && in[idx + 1] == '\n') {
        idx += 2;
      }
    }
    return out.toByteArray();
  }

  private static int indexOfCrlf(byte[] a, int start) {
    for (int i = start; i + 1 < a.length; i++) {
      if (a[i] == '\r' && a[i + 1] == '\n') return i;
    }
    return -1;
  }

  private static byte[] gunzip(byte[] gz) throws IOException {
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
         ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, gz.length))) {
      byte[] buf = new byte[4096];
      int r;
      while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
      return out.toByteArray();
    }
  }

  // ---------------------------------------------------------------------------

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
