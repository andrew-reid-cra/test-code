package ca.gc.cra.radar.domain.events;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable user-facing event emitted from reconstructed HTTP traffic.
 *
 * <p><strong>Why:</strong> Downstream telemetry and audit pipelines ingest structured events derived from
 * captured traffic to drive analytics, threat detection, and compliance reporting.</p>
 * <p><strong>Thread-safety:</strong> Records are immutable and safe to share across threads.</p>
 *
 * @param timestamp capture timestamp in UTC; never {@code null}
 * @param eventType canonical event type (e.g., {@code user.login}); never {@code null}
 * @param ruleId identifier of the matched rule; never {@code null}
 * @param ruleDescription human-readable description of the rule; may be {@code null}
 * @param sessionId resolved session identifier; never {@code null}
 * @param authState authentication classification; never {@code null}
 * @param userId optional user identifier; may be {@code null}
 * @param http HTTP metadata bundle; never {@code null}
 * @param attributes additional key/value attributes; never {@code null}
 * @param source logical source of events (e.g., {@code http-gateway}); never {@code null}
 * @param app originating application/service identifier; never {@code null}
 * @param traceId optional distributed trace identifier carried by the HTTP request
 * @param requestId optional request identifier carried by the HTTP request
 *
 * @since RADAR 1.1.0
 */
public record UserEvent(
    Instant timestamp,
    String eventType,
    String ruleId,
    String ruleDescription,
    String sessionId,
    AuthState authState,
    String userId,
    UserEventHttpMetadata http,
    Map<String, String> attributes,
    String source,
    String app,
    String traceId,
    String requestId) {

  /**
   * Validates constructor invariants and defensively copies attribute maps.
   */
  public UserEvent {
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    eventType = Objects.requireNonNull(eventType, "eventType");
    ruleId = Objects.requireNonNull(ruleId, "ruleId");
    sessionId = Objects.requireNonNull(sessionId, "sessionId");
    authState = Objects.requireNonNull(authState, "authState");
    http = Objects.requireNonNull(http, "http");
    source = Objects.requireNonNull(source, "source");
    app = Objects.requireNonNull(app, "app");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
