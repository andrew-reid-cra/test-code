package ca.gc.cra.radar.application.events;

import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.rules.RuleMatchResult;
import ca.gc.cra.radar.application.events.rules.UserEventRuleEngine;
import ca.gc.cra.radar.domain.events.SessionInfo;
import ca.gc.cra.radar.domain.events.UserEvent;
import ca.gc.cra.radar.domain.events.UserEventHttpMetadata;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Assembles immutable {@link UserEvent} instances from rule matches and HTTP exchanges.
 *
 * @since RADAR 1.1.0
 */
public final class UserEventAssembler {
  private UserEventAssembler() {}

  /**
   * Builds a user event using the supplied rule engine, exchange context, and resolver.
   *
   * @param engine rule engine providing defaults and context
   * @param exchange HTTP exchange to annotate
   * @param match rule match containing extracted attributes
   * @param sessionResolver session resolver used to derive session metadata
   * @return assembled user event
   */
  public static UserEvent build(
      UserEventRuleEngine engine,
      HttpExchangeContext exchange,
      RuleMatchResult match,
      SessionResolver sessionResolver) {
    SessionInfo session = sessionResolver.resolve(exchange, match.userId());
    Map<String, String> attributes = new LinkedHashMap<>(match.attributes());

    String app = engine.defaults() != null ? engine.defaults().app() : "";
    String source = engine.defaults() != null ? engine.defaults().source() : "";
    app = extractAttribute(attributes, "app", app);
    source = extractAttribute(attributes, "source", source);

    UserEventHttpMetadata http = resolveHttp(exchange);
    String traceId = resolveTraceId(exchange);
    String requestId = resolveRequestId(exchange);

    return new UserEvent(
        resolveTimestamp(exchange),
        match.eventType(),
        match.ruleId(),
        match.ruleDescription(),
        session.sessionId(),
        session.authState(),
        session.userId(),
        http,
        attributes,
        source,
        app,
        traceId,
        requestId);
  }

  private static Instant resolveTimestamp(HttpExchangeContext exchange) {
    long micros = exchange.hasResponse() && exchange.response() != null
        ? exchange.response().timestampMicros()
        : exchange.request().timestampMicros();
    if (micros <= 0) {
      return Instant.now();
    }
    long nanos = micros * 1_000L;
    return Instant.ofEpochSecond(0, nanos);
  }

  private static UserEventHttpMetadata resolveHttp(HttpExchangeContext exchange) {
    int status = exchange.hasResponse() && exchange.response() != null ? exchange.response().status() : -1;
    long latencyMicros = exchange.latencyMicros();
    long latencyMillis = latencyMicros >= 0 ? TimeUnit.MICROSECONDS.toMillis(latencyMicros) : -1L;
    return new UserEventHttpMetadata(
        exchange.request().method(),
        exchange.request().path(),
        status,
        latencyMillis);
  }

  private static String resolveTraceId(HttpExchangeContext exchange) {
    Optional<String> traceparent = exchange.request().header("traceparent");
    if (traceparent.isPresent()) {
      String[] parts = traceparent.get().split("-");
      if (parts.length >= 2 && parts[1].length() == 32) {
        return parts[1];
      }
    }
    Optional<String> b3 = exchange.request().header("x-b3-traceid");
    if (b3.isPresent()) {
      return b3.get();
    }
    return exchange.request().header("x-trace-id").orElse(null);
  }

  private static String resolveRequestId(HttpExchangeContext exchange) {
    return exchange.request().header("x-request-id").orElseGet(() -> exchange.request().header("request-id").orElse(null));
  }

  private static String extractAttribute(Map<String, String> attributes, String key, String fallback) {
    String value = attributes.remove(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value;
  }
}
