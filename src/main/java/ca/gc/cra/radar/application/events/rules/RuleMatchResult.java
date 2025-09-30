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

  /**
   * Validates required fields and protects internal state from external mutation.
   *
   * @param ruleId identifier of the rule that produced the match
   * @param ruleDescription optional human-readable description
   * @param eventType emitted event type
   * @param userId optional user identifier derived from the exchange
   * @param attributes attribute map to copy defensively
   */
  public RuleMatchResult {
    ruleId = Objects.requireNonNull(ruleId, "ruleId");
    eventType = Objects.requireNonNull(eventType, "eventType");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
