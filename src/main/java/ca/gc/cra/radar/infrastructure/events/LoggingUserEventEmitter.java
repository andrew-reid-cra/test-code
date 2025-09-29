package ca.gc.cra.radar.infrastructure.events;

import ca.gc.cra.radar.application.port.UserEventEmitter;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.events.UserEvent;
import ca.gc.cra.radar.domain.events.UserEventHttpMetadata;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits user events to structured logs and increments metrics counters.
 *
 * @since RADAR 1.1.0
 */
public final class LoggingUserEventEmitter implements UserEventEmitter {
  private static final Logger log = LoggerFactory.getLogger(LoggingUserEventEmitter.class);

  private final MetricsPort metrics;
  private final String metricPrefix;

  /**
   * Creates a logging emitter using the supplied metrics port and prefix.
   *
   * @param metrics metrics adapter; falls back to {@link MetricsPort#NO_OP} when {@code null}
   * @param metricPrefix prefix for emitted counters (e.g., {@code userEvents})
   */
  public LoggingUserEventEmitter(MetricsPort metrics, String metricPrefix) {
    this.metrics = metrics == null ? MetricsPort.NO_OP : metrics;
    this.metricPrefix = metricPrefix == null || metricPrefix.isBlank() ? "userEvents" : metricPrefix.trim();
  }

  /**
   * Creates a logging emitter using {@code userEvents} as the metric prefix.
   *
   * @param metrics metrics adapter; falls back to {@link MetricsPort#NO_OP} when {@code null}
   */
  public LoggingUserEventEmitter(MetricsPort metrics) {
    this(metrics, "userEvents");
  }

  /**
   * Creates a logging emitter without metrics.
   */
  public LoggingUserEventEmitter() {
    this(MetricsPort.NO_OP, "userEvents");
  }

  @Override
  public void emit(UserEvent event) {
    Objects.requireNonNull(event, "event");
    metrics.increment(metricPrefix + ".emitted");

    UserEventHttpMetadata http = event.http();
    StringJoiner joiner = new StringJoiner(", ");
    joiner.add("type=" + event.eventType());
    joiner.add("rule=" + event.ruleId());
    if (event.ruleDescription() != null && !event.ruleDescription().isBlank()) {
      joiner.add("ruleDesc=" + event.ruleDescription());
    }
    joiner.add("session=" + event.sessionId());
    joiner.add("auth=" + event.authState());
    if (event.userId() != null) {
      joiner.add("user=" + event.userId());
    }
    joiner.add("source=" + event.source());
    joiner.add("app=" + event.app());
    if (http != null) {
      joiner.add("method=" + http.method());
      joiner.add("path=" + http.path());
      joiner.add("status=" + http.status());
      joiner.add("latencyMs=" + http.latencyMillis());
    }
    if (event.traceId() != null) {
      joiner.add("traceId=" + event.traceId());
    }
    if (event.requestId() != null) {
      joiner.add("requestId=" + event.requestId());
    }
    if (!event.attributes().isEmpty()) {
      joiner.add("attributes=" + formatAttributes(event.attributes()));
    }

    log.info("user.event {}", joiner);
  }

  private String formatAttributes(Map<String, String> attributes) {
    StringJoiner joiner = new StringJoiner(";", "[", "]");
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      joiner.add(entry.getKey() + '=' + entry.getValue());
    }
    return joiner.toString();
  }
}
