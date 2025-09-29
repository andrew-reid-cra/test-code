package ca.gc.cra.radar.domain.events;

import java.util.Objects;

/**
 * HTTP metadata associated with a reconstructed user event.
 *
 * <p><strong>Why:</strong> Downstream sinks compute KPIs from request method, resource path, response status,
 * and round-trip latency.</p>
 *
 * @param method HTTP method verb (e.g., {@code GET}); never {@code null}
 * @param path normalized request path without query parameters; never {@code null}
 * @param status HTTP status code; negative values indicate missing response metadata
 * @param latencyMillis request/response latency in milliseconds; negative when unavailable
 *
 * @since RADAR 1.1.0
 */
public record UserEventHttpMetadata(String method, String path, int status, long latencyMillis) {

  /**
   * Validates required fields and normalizes optional numeric values.
   */
  public UserEventHttpMetadata {
    method = Objects.requireNonNull(method, "method");
    path = Objects.requireNonNull(path, "path");
  }
}
