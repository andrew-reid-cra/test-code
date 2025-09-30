package ca.gc.cra.radar.infrastructure.events;

import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.TerminalEventEmitter;
import ca.gc.cra.radar.domain.events.TerminalEvent;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits terminal events as structured logs and updates observability metrics.
 *
 * @since RADAR 1.2.0
 */
public final class LoggingTerminalEventEmitter implements TerminalEventEmitter {
  private static final Logger log = LoggerFactory.getLogger(LoggingTerminalEventEmitter.class);

  private final MetricsPort metrics;
  private final String metricPrefix;

  /**
   * Creates a logging emitter using the supplied metrics port and prefix.
   *
   * @param metrics metrics adapter; falls back to {@link MetricsPort#NO_OP} when {@code null}
   * @param metricPrefix prefix for emitted counters (e.g., {@code terminalEvents})
   */
  public LoggingTerminalEventEmitter(MetricsPort metrics, String metricPrefix) {
    this.metrics = metrics == null ? MetricsPort.NO_OP : metrics;
    this.metricPrefix =
        metricPrefix == null || metricPrefix.isBlank() ? "terminalEvents" : metricPrefix.trim();
  }

  /**
   * Creates a logging emitter using {@code terminalEvents} as the metric prefix.
   *
   * @param metrics metrics adapter; falls back to {@link MetricsPort#NO_OP} when {@code null}
   */
  public LoggingTerminalEventEmitter(MetricsPort metrics) {
    this(metrics, "terminalEvents");
  }

  /**
   * Creates a logging emitter without metrics.
   */
  public LoggingTerminalEventEmitter() {
    this(MetricsPort.NO_OP, "terminalEvents");
  }

  @Override
  public void emit(TerminalEvent event) {
    Objects.requireNonNull(event, "event");
    metrics.increment(metricPrefix + ".emitted");
    metrics.observe(metricPrefix + ".attributes.size", event.attributes().size());

    StringJoiner joiner = new StringJoiner(", ");
    joiner.add("timestamp=" + event.timestamp());
    joiner.add("type=" + event.eventType());
    joiner.add("terminal=" + event.terminalId());
    joiner.add("session=" + event.sessionId());
    joiner.add("source=" + event.source());
    joiner.add("app=" + event.app());

    if (event.operatorId() != null && !event.operatorId().isBlank()) {
      joiner.add("operator=" + event.operatorId());
    }
    if (event.traceId() != null && !event.traceId().isBlank()) {
      joiner.add("traceId=" + event.traceId());
    }
    if (!event.attributes().isEmpty()) {
      joiner.add("attributes=" + formatAttributes(event.attributes()));
    }

    log.info("terminal.event {}", joiner);
  }

  private String formatAttributes(Map<String, String> attributes) {
    StringJoiner joiner = new StringJoiner(";", "[", "]");
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      joiner.add(entry.getKey() + '=' + entry.getValue());
    }
    return joiner.toString();
  }
}