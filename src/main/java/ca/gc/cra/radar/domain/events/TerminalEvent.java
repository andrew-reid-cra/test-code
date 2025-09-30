package ca.gc.cra.radar.domain.events;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * <strong>What:</strong> Immutable terminal interaction event emitted from 3270/green-screen capture streams.
 * <p><strong>Why:</strong> Downstream analytics and monitoring components consume structured terminal events to
 * trace operator activity, support audit workflows, and surface anomalies.</p>
 * <p><strong>Thread-safety:</strong> Records are immutable and safe to share across threads.</p>
 *
 * @param timestamp capture timestamp in UTC; never {@code null}
 * @param terminalId terminal identifier (LU name or TN3270 connection id); never {@code null}
 * @param sessionId resolved session identifier used to correlate related interactions; never {@code null}
 * @param eventType canonical event category (e.g., {@code terminal.input}); never {@code null}
 * @param operatorId optional operator identifier resolved from the payload; may be {@code null}
 * @param source logical source of events (e.g., {@code tn3270-gateway}); never {@code null}
 * @param app originating application identifier; never {@code null}
 * @param attributes additional key/value attributes describing screen state or derived context; never {@code null}
 * @param traceId optional distributed trace identifier propagated from upstream systems
 *
 * @since RADAR 1.2.0
 */
public record TerminalEvent(
    Instant timestamp,
    String terminalId,
    String sessionId,
    String eventType,
    String operatorId,
    String source,
    String app,
    Map<String, String> attributes,
    String traceId) {

  /**
   * Validates constructor invariants and defensively copies attribute maps.
   */
  public TerminalEvent {
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    terminalId = Objects.requireNonNull(terminalId, "terminalId");
    sessionId = Objects.requireNonNull(sessionId, "sessionId");
    eventType = Objects.requireNonNull(eventType, "eventType");
    source = Objects.requireNonNull(source, "source");
    app = Objects.requireNonNull(app, "app");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}