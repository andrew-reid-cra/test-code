package ca.gc.cra.radar.application.events.http;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds {@link HttpExchangeContext} instances from {@link MessagePair} payloads.
 *
 * @since RADAR 1.1.0
 */
public final class HttpExchangeBuilder {
  private HttpExchangeBuilder() {}

  /**
   * Parses the supplied message pair into an HTTP exchange when possible.
   *
   * @param pair message pair containing HTTP request/response payloads
   * @return optional exchange context; empty when parsing fails or request missing
   */
  public static Optional<HttpExchangeContext> from(MessagePair pair) {
    if (pair == null || pair.request() == null || pair.request().payload() == null) {
      return Optional.empty();
    }

    Optional<HttpRequestContext> request = parseRequest(pair.request());
    if (request.isEmpty()) {
      return Optional.empty();
    }

    Optional<HttpResponseContext> response = pair.response() == null || pair.response().payload() == null
        ? Optional.empty()
        : parseResponse(pair.response());

    return Optional.of(new HttpExchangeContext(request.get(), response.orElse(null)));
  }

  private static Optional<HttpRequestContext> parseRequest(MessageEvent event) {
    ByteStream stream = event.payload();
    byte[] data = stream.data();
    ParsedMessage parsed = parseMessage(data);
    if (parsed == null || parsed.firstLine == null) {
      return Optional.empty();
    }

    String[] parts = splitTokens(parsed.firstLine, 3);
    if (parts.length < 2) {
      return Optional.empty();
    }
    String method = parts[0];
    String rawTarget = parts[1];
    String httpVersion = parts.length >= 3 ? parts[2] : "HTTP/1.1";

    TargetComponents target = parseTarget(rawTarget);
    Map<String, List<String>> headers = parsed.headers;
    Charset charset = resolveCharset(headers);
    HttpBodyView body = new HttpBodyView(parsed.body, charset);
    Map<String, String> cookies = parseCookies(headers.getOrDefault("cookie", List.of()));
    Map<String, String> query = target.query;

    RequestAddresses addresses = resolveRequestAddresses(stream);

    HttpRequestContext context = new HttpRequestContext(
        method,
        target.path,
        rawTarget,
        httpVersion,
        headers,
        cookies,
        query,
        body,
        addresses.clientIp,
        addresses.clientPort,
        addresses.serverIp,
        addresses.serverPort,
        stream.timestampMicros());
    return Optional.of(context);
  }

  private static Optional<HttpResponseContext> parseResponse(MessageEvent event) {
    ByteStream stream = event.payload();
    byte[] data = stream.data();
    ParsedMessage parsed = parseMessage(data);
    if (parsed == null || parsed.firstLine == null) {
      return Optional.empty();
    }

    String[] parts = splitTokens(parsed.firstLine, 3);
    if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
      return Optional.empty();
    }
    String httpVersion = parts[0];
    int status;
    try {
      status = Integer.parseInt(parts[1]);
    } catch (NumberFormatException ex) {
      status = -1;
    }
    String reason = parts.length >= 3 ? parts[2].trim() : "";

    Map<String, List<String>> headers = parsed.headers;
    Charset charset = resolveCharset(headers);
    HttpBodyView body = new HttpBodyView(parsed.body, charset);

    HttpResponseContext context = new HttpResponseContext(
        status,
        reason,
        httpVersion,
        headers,
        body,
        stream.timestampMicros());
    return Optional.of(context);
  }

  private static ParsedMessage parseMessage(byte[] data) {
    if (data == null) {
      return null;
    }
    int boundary = findHeaderBoundary(data);
    int headerLen = boundary >= 0 ? boundary : data.length;
    String headerBlock = new String(data, 0, headerLen, StandardCharsets.ISO_8859_1);
    String[] lines = headerBlock.split("\\r\\n");
    if (lines.length == 0) {
      return null;
    }
    String firstLine = lines[0];
    Map<String, List<String>> headers = new LinkedHashMap<>();
    String currentName = null;
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      if (line.isEmpty()) {
        continue;
      }
      if ((line.startsWith(" ") || line.startsWith("\t")) && currentName != null) {
        List<String> values = headers.get(currentName);
        int lastIndex = values.size() - 1;
        values.set(lastIndex, values.get(lastIndex) + line.trim());
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
      currentName = name;
    }

    byte[] body;
    if (boundary >= 0 && boundary < data.length) {
      int bodyLen = data.length - boundary;
      body = new byte[bodyLen];
      System.arraycopy(data, boundary, body, 0, bodyLen);
    } else {
      body = new byte[0];
    }

    Map<String, List<String>> immutableHeaders = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      immutableHeaders.put(entry.getKey(), List.copyOf(entry.getValue()));
    }

    return new ParsedMessage(firstLine, Map.copyOf(immutableHeaders), body);
  }

  private static int findHeaderBoundary(byte[] data) {
    for (int i = 0; i + 3 < data.length; i++) {
      if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
        return i + 4;
      }
    }
    return -1;
  }

  private static Charset resolveCharset(Map<String, List<String>> headers) {
    List<String> contentType = headers.get("content-type");
    if (contentType != null) {
      for (String value : contentType) {
        int idx = value.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
          String charset = value.substring(idx + 8).trim();
          int semicolon = charset.indexOf(';');
          if (semicolon >= 0) {
            charset = charset.substring(0, semicolon).trim();
          }
          try {
            return Charset.forName(charset);
          } catch (Exception ignore) {
            // fall back
          }
        }
      }
    }
    return StandardCharsets.UTF_8;
  }

  private static Map<String, String> parseCookies(List<String> headerValues) {
    Map<String, String> cookies = new LinkedHashMap<>();
    if (headerValues == null) {
      return cookies;
    }
    for (String header : headerValues) {
      if (header == null) {
        continue;
      }
      String[] parts = header.split(";");
      for (String part : parts) {
        String trimmed = part.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String name = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String value = trimmed.substring(eq + 1).trim();
        cookies.put(name, value);
      }
    }
    return cookies;
  }

  private static TargetComponents parseTarget(String rawTarget) {
    String path = rawTarget;
    Map<String, String> query = Map.of();
    int question = rawTarget.indexOf('?');
    if (question >= 0) {
      path = rawTarget.substring(0, question);
      String queryString = rawTarget.substring(question + 1);
      query = parseQuery(queryString);
    }
    return new TargetComponents(path, query);
  }

  private static Map<String, String> parseQuery(String queryString) {
    if (queryString == null || queryString.isEmpty()) {
      return Map.of();
    }
    Map<String, String> query = new LinkedHashMap<>();
    String[] parts = queryString.split("&");
    for (String part : parts) {
      if (part.isEmpty()) {
        continue;
      }
      int eq = part.indexOf('=');
      String key;
      String value;
      if (eq >= 0) {
        key = part.substring(0, eq);
        value = part.substring(eq + 1);
      } else {
        key = part;
        value = "";
      }
      try {
        key = URLDecoder.decode(key, StandardCharsets.UTF_8);
        value = URLDecoder.decode(value, StandardCharsets.UTF_8);
      } catch (IllegalArgumentException ex) {
        // leave undecoded on malformed sequences
      }
      if (!key.isEmpty()) {
        query.put(key.toLowerCase(Locale.ROOT), value);
      }
    }
    return Map.copyOf(query);
  }

  private static String[] splitTokens(String line, int limit) {
    return line.trim().split("\\s+", limit);
  }

  private static RequestAddresses resolveRequestAddresses(ByteStream stream) {
    FiveTuple flow = stream.flow();
    boolean fromClient = stream.fromClient();
    String clientIp = fromClient ? flow.srcIp() : flow.dstIp();
    int clientPort = fromClient ? flow.srcPort() : flow.dstPort();
    String serverIp = fromClient ? flow.dstIp() : flow.srcIp();
    int serverPort = fromClient ? flow.dstPort() : flow.srcPort();
    return new RequestAddresses(clientIp, clientPort, serverIp, serverPort);
  }

  private record ParsedMessage(String firstLine, Map<String, List<String>> headers, byte[] body) {
    ParsedMessage {
      headers = headers != null ? Map.copyOf(headers) : Map.of();
      body = body != null ? body.clone() : new byte[0];
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ParsedMessage(
          String otherFirstLine,
          Map<String, List<String>> otherHeaders,
          byte[] otherBody))) {
        return false;
      }
      return Objects.equals(firstLine, otherFirstLine)
          && Objects.equals(headers, otherHeaders)
          && Arrays.equals(body, otherBody);
    }

    @Override
    public int hashCode() {
      int result = Objects.hashCode(firstLine);
      result = 31 * result + Objects.hashCode(headers);
      result = 31 * result + Arrays.hashCode(body);
      return result;
    }

    @Override
    public String toString() {
      return "ParsedMessage{"
          + "firstLine='" + firstLine + '\''
          + ", headers=" + headers
          + ", body=" + Arrays.toString(body)
          + '}';
    }
  }

  private record TargetComponents(String path, Map<String, String> query) {}

  private record RequestAddresses(String clientIp, int clientPort, String serverIp, int serverPort) {}
}
