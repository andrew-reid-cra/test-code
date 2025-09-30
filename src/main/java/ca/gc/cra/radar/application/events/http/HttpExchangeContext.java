package ca.gc.cra.radar.application.events.http;

import java.util.Objects;

/**
 * Aggregate view of a reconstructed HTTP exchange containing request/response metadata.
 *
 * @since RADAR 1.1.0
 */
public final class HttpExchangeContext {
  private final HttpRequestContext request;
  private final HttpResponseContext response;
  private final long latencyMicros;

  /**
   * Creates an exchange context and computes latency when both sides are present.
   *
   * @param request request context; never {@code null}
   * @param response response context; may be {@code null}
   */
  public HttpExchangeContext(HttpRequestContext request, HttpResponseContext response) {
    this.request = Objects.requireNonNull(request, "request");
    this.response = response;
    this.latencyMicros = response == null
        ? -1L
        : Math.max(0L, response.timestampMicros() - request.timestampMicros());
  }

  /**
   * Returns the request context that triggered the exchange.
   *
   * @return immutable request metadata captured from the reconstructed session
   */
  public HttpRequestContext request() {
    return request;
  }

  /**
   * Returns the response context when the server reply was captured.
   *
   * @return immutable response metadata or {@code null} when unavailable
   */
  public HttpResponseContext response() {
    return response;
  }

  /**
   * Returns latency in microseconds or {@code -1} when unavailable.
   *
   * @return latency in microseconds
   */
  public long latencyMicros() {
    return latencyMicros;
  }

  /**
   * Indicates whether the exchange contains a reconstructed server response.
   *
   * @return {@code true} when a response was reconstructed
   */
  public boolean hasResponse() {
    return response != null;
  }
}
