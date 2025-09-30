package ca.gc.cra.radar.application.events.http;

import ca.gc.cra.radar.application.events.json.JsonSupport;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Lazily materialized HTTP body view supporting string and JSON representations on demand.
 *
 * <p><strong>Why:</strong> Rule evaluation requires body content but must avoid unnecessary allocations.</p>
 * <p><strong>Thread-safety:</strong> Instances are thread-safe for concurrent reads and memoize decoded forms.</p>
 *
 * @since RADAR 1.1.0
 */
public final class HttpBodyView {
  private static final Object UNPARSEABLE = new Object();

  private final byte[] data;
  private final Charset charset;
  private volatile String cachedString;
  private volatile Object cachedJson;
  private volatile boolean jsonResolved;

  /**
   * Creates a body view backed by the supplied byte array.
   *
   * @param data raw body bytes; when {@code null} an empty array is used
   * @param charset character set used for string decoding; {@code null} falls back to UTF-8
   */
  public HttpBodyView(byte[] data, Charset charset) {
    this.data = data == null ? new byte[0] : data.clone();
    this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
  }

  /**
   * Returns {@code true} when the body length is zero.
   *
   * @return {@code true} if the body is empty
   */
  public boolean isEmpty() {
    return data.length == 0;
  }

  /**
   * Returns the number of bytes in the body.
   *
   * @return body length in bytes
   */
  public int length() {
    return data.length;
  }

  /**
   * Provides the raw backing bytes without copying.
   *
   * @return raw byte array (do not mutate)
   */
  public byte[] bytes() {
    return data.clone();
  }

  /**
   * Decodes and memoizes the body using the configured charset.
   *
   * @return decoded string (empty string when the body is empty)
   */
  public String asString() {
    String local = cachedString;
    if (local != null) {
      return local;
    }
    synchronized (this) {
      local = cachedString;
      if (local == null) {
        local = data.length == 0 ? "" : new String(data, charset);
        cachedString = local;
      }
      return local;
    }
  }

  /**
   * Attempts to parse the body as JSON, caching the result.
   *
   * @param jsonSupport helper capable of parsing JSON strings; must not be {@code null}
   * @return optional parsed JSON tree (nested {@link java.util.Map}, {@link java.util.List}, primitives)
   */
  public Optional<Object> asJson(JsonSupport jsonSupport) {
    if (jsonSupport == null || data.length == 0) {
      return Optional.empty();
    }
    if (jsonResolved) {
      Object local = cachedJson;
      return local == UNPARSEABLE ? Optional.empty() : Optional.of(local);
    }
    synchronized (this) {
      if (!jsonResolved) {
        try {
          Object parsed = jsonSupport.parse(asString());
          cachedJson = parsed;
        } catch (Exception ex) {
          cachedJson = UNPARSEABLE;
        } finally {
          jsonResolved = true;
        }
      }
      Object local = cachedJson;
      return local == UNPARSEABLE ? Optional.empty() : Optional.of(local);
    }
  }
}
