package ca.gc.cra.radar.application.events.rules;

import java.util.Map;
import java.util.Objects;

/**
 * Result produced when a rule matches an HTTP exchange.
 *
 * @since RADAR 1.1.0
 */
public record RuleMatchResult(
    String ruleId,
    String ruleDescription,
    String eventType,
    String userId,
    Map<String, String> attributes) {

  public RuleMatchResult {
    ruleId = Objects.requireNonNull(ruleId, "ruleId");
    eventType = Objects.requireNonNull(eventType, "eventType");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
