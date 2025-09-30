package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.events.tn3270.ScreenRuleEngine;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleEngine.ScreenRuleMatch;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.TerminalEventEmitter;
import ca.gc.cra.radar.application.port.Tn3270EventSink;
import ca.gc.cra.radar.domain.events.TerminalEvent;
import ca.gc.cra.radar.domain.protocol.tn3270.ScreenSnapshot;
import ca.gc.cra.radar.domain.protocol.tn3270.SessionKey;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Event;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270EventType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>What:</strong> Decorator that emits {@link TerminalEvent} instances when TN3270 submit events are observed.
 * <p><strong>Why:</strong> Bridges TN3270 assembler output to terminal observability pipelines while preserving the
 * existing Kafka sink.</p>
 * <p><strong>Thread-safety:</strong> Accept method is thread-safe via concurrent session tracking.</p>
 * <p><strong>Performance:</strong> Reuses cached screen snapshots per session to avoid redundant rule evaluations.</p>
 *
 * @since RADAR 1.3.0
 */
public final class Tn3270ScreenEventPublishingSink implements Tn3270EventSink {
  private static final Logger log = LoggerFactory.getLogger(Tn3270ScreenEventPublishingSink.class);
  private static final String TERMINAL_EVENT_TYPE = "terminal.input";

  private final Tn3270EventSink delegate;
  private final ScreenRuleEngine ruleEngine;
  private final TerminalEventEmitter emitter;
  private final MetricsPort metrics;
  private final String metricPrefix;
  private final String source;
  private final String app;
  private final ConcurrentHashMap<SessionKey, ScreenContext> sessions = new ConcurrentHashMap<>();

  /**
   * Creates a publishing sink.
   *
   * @param delegate downstream TN3270 event sink; must not be {@code null}
   * @param ruleEngine compiled screen rule engine; must not be {@code null}
   * @param emitter terminal event emitter; must not be {@code null}
   * @param metrics metrics adapter used for instrumentation; falls back to {@link MetricsPort#NO_OP}
   * @param metricPrefix prefix for emitted metrics (e.g., {@code tn3270Terminals})
   * @param source logical source value injected into emitted terminal events
   * @param app application identifier injected into emitted terminal events
   */
  public Tn3270ScreenEventPublishingSink(
      Tn3270EventSink delegate,
      ScreenRuleEngine ruleEngine,
      TerminalEventEmitter emitter,
      MetricsPort metrics,
      String metricPrefix,
      String source,
      String app) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.metrics = metrics == null ? MetricsPort.NO_OP : metrics;
    this.metricPrefix =
        metricPrefix == null || metricPrefix.isBlank() ? "tn3270Terminals.publisher" : metricPrefix.trim();
    this.source = source == null || source.isBlank() ? "tn3270-assembler" : source.trim();
    this.app = app == null || app.isBlank() ? "radar" : app.trim();
  }

  @Override
  public void accept(Tn3270Event event) throws Exception {
    Objects.requireNonNull(event, "event");
    try {
      processEvent(event);
    } catch (RuntimeException ex) {
      metrics.increment(metricPrefix + ".processing.error");
      log.warn("TN3270 terminal publishing failed for session {}", event.session().canonical(), ex);
    }

    try {
      delegate.accept(event);
    } finally {
      if (event.type() == Tn3270EventType.SESSION_END || event.type() == Tn3270EventType.SESSION_START) {
        sessions.remove(event.session());
      }
    }
  }

  private void processEvent(Tn3270Event event) {
    switch (event.type()) {
      case SCREEN_RENDER -> cacheScreen(event);
      case USER_SUBMIT -> publishTerminalEvent(event);
      case SESSION_START -> metrics.increment(metricPrefix + ".session.start");
      case SESSION_END -> metrics.increment(metricPrefix + ".session.end");
      default -> {
        // ignore
      }
    }
  }

  private void cacheScreen(Tn3270Event event) {
    ScreenSnapshot snapshot = event.screen();
    if (snapshot == null) {
      metrics.increment(metricPrefix + ".render.skipped.noSnapshot");
      return;
    }
    ScreenRuleMatch match = null;
    if (ruleEngine.hasRules()) {
      try {
        match = ruleEngine.match(snapshot).orElse(null);
        if (match != null) {
          metrics.increment(metricPrefix + ".render.match");
        } else {
          metrics.increment(metricPrefix + ".render.noMatch");
        }
      } catch (RuntimeException ex) {
        metrics.increment(metricPrefix + ".render.error");
        log.warn("TN3270 screen rule evaluation failed for session {}", event.session().canonical(), ex);
      }
    } else {
      metrics.increment(metricPrefix + ".render.rulesDisabled");
    }
    sessions.put(event.session(), new ScreenContext(snapshot, match));
    metrics.increment(metricPrefix + ".render.captured");
  }

  private void publishTerminalEvent(Tn3270Event event) {
    metrics.increment(metricPrefix + ".submit.received");
    ScreenContext context = sessions.get(event.session());
    if (context == null || context.snapshot == null) {
      metrics.increment(metricPrefix + ".submit.skipped.noScreen");
      return;
    }

    ScreenRuleMatch match = context.match;
    if (match != null) {
      metrics.increment(metricPrefix + ".submit.match");
    } else {
      metrics.increment(metricPrefix + ".submit.noMatch");
    }

    Map<String, String> attributes = buildAttributes(event, context, match);
    String operatorId = resolveOperatorId(event, match, attributes);
    TerminalEvent terminalEvent = new TerminalEvent(
        toInstant(event.timestamp()),
        resolveTerminalId(event.session()),
        event.session().sessionId(),
        TERMINAL_EVENT_TYPE,
        operatorId,
        source,
        app,
        attributes,
        null);

    try {
      emitter.emit(terminalEvent);
      metrics.increment(metricPrefix + ".emitted");
      metrics.observe(metricPrefix + ".attributes.size", attributes.size());
    } catch (RuntimeException ex) {
      metrics.increment(metricPrefix + ".emit.error");
      log.warn("Terminal event emitter threw exception for session {}", event.session().canonical(), ex);
    }
  }

  private String resolveOperatorId(Tn3270Event event, ScreenRuleMatch match, Map<String, String> attributes) {
    if (match != null) {
      String userId = blankToNull(match.userId());
      if (userId != null) {
        return userId;
      }
      String labelKey = blankToNull(match.label());
      if (labelKey != null) {
        String value = event.inputFields().get(labelKey);
        String sanitized = blankToNull(value);
        if (sanitized != null) {
          return sanitized;
        }
        String normalizedLabel = sanitizeFieldKey(labelKey);
        if (normalizedLabel != null) {
          String attributeKey = "input." + normalizedLabel;
          String attributeValue = blankToNull(attributes.get(attributeKey));
          if (attributeValue != null) {
            return attributeValue;
          }
          for (Map.Entry<String, String> entry : event.inputFields().entrySet()) {
            String normalizedKey = sanitizeFieldKey(entry.getKey());
            if (normalizedLabel.equals(normalizedKey)) {
              String candidate = blankToNull(entry.getValue());
              if (candidate != null) {
                return candidate;
              }
            }
          }
        }
      }
    }
    for (String value : event.inputFields().values()) {
      String candidate = blankToNull(value);
      if (candidate != null) {
        return candidate;
      }
    }
    return null;
  }
  private Map<String, String> buildAttributes(
      Tn3270Event event, ScreenContext context, ScreenRuleMatch match) {
    Map<String, String> attributes = new LinkedHashMap<>(8 + event.inputFields().size());
    if (match != null) {
      attributes.put("screen.id", match.screenId());
      String description = blankToNull(match.description());
      if (description != null) {
        attributes.put("screen.description", description);
      }
      String label = blankToNull(match.label());
      if (label != null) {
        attributes.put("screen.label", label);
      }
    }

    String screenName = blankToNull(event.screenName());
    if (screenName != null) {
      attributes.put("screen.name", screenName);
    }
    String screenHash = blankToNull(event.screenHash());
    if (screenHash != null) {
      attributes.put("screen.hash", screenHash);
    }

    attributes.put("screen.rows", Integer.toString(context.snapshot.rows()));
    attributes.put("screen.cols", Integer.toString(context.snapshot.cols()));

    if (event.aid() != null) {
      attributes.put("tn3270.aid", event.aid().name());
    }

    for (Map.Entry<String, String> entry : event.inputFields().entrySet()) {
      String sanitizedKey = sanitizeFieldKey(entry.getKey());
      if (sanitizedKey == null) {
        continue;
      }
      attributes.put("input." + sanitizedKey, safeAttributeValue(entry.getValue()));
    }
    return Map.copyOf(attributes);
  }

  private Instant toInstant(long micros) {
    long seconds = Math.floorDiv(micros, 1_000_000L);
    long nanos = Math.floorMod(micros, 1_000_000L) * 1_000L;
    return Instant.ofEpochSecond(seconds, nanos);
  }

  private String resolveTerminalId(SessionKey session) {
    return session.luName().filter(value -> !value.isBlank()).orElse(session.canonical());
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String sanitizeFieldKey(String rawKey) {
    if (rawKey == null) {
      return null;
    }
    String trimmed = rawKey.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    StringBuilder builder = new StringBuilder(trimmed.length());
    boolean lastSeparator = true;
    for (int i = 0; i < trimmed.length() && builder.length() < 64; i++) {
      char c = trimmed.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        builder.append(Character.toLowerCase(c));
        lastSeparator = false;
      } else if (!lastSeparator) {
        builder.append('_');
        lastSeparator = true;
      }
    }
    int length = builder.length();
    if (length == 0) {
      return null;
    }
    if (builder.charAt(length - 1) == '_') {
      builder.setLength(length - 1);
    }
    return builder.length() == 0 ? null : builder.toString();
  }

  private static String safeAttributeValue(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '\r' || c == '\n' || Character.isISOControl(c)) {
        builder.append(' ');
      } else {
        builder.append(c);
      }
    }
    return builder.toString();
  }

  @Override
  public void close() throws Exception {
    sessions.clear();
    try {
      emitter.close();
    } catch (Exception ex) {
      log.warn("Terminal event emitter close failed", ex);
    }
    delegate.close();
  }

  private record ScreenContext(ScreenSnapshot snapshot, ScreenRuleMatch match) {}
}





