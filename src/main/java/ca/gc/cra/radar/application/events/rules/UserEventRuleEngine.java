package ca.gc.cra.radar.application.events.rules;

import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.json.JsonSupport;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe engine that evaluates HTTP exchanges against a compiled rule set.
 *
 * @since RADAR 1.1.0
 */
public final class UserEventRuleEngine {
  private final JsonSupport jsonSupport = new JsonSupport();
  private final AtomicReference<CompiledRuleSet> current = new AtomicReference<>();

  /**
   * Creates an engine with the supplied rule set.
   *
   * @param ruleSet compiled rule set; may be {@code null} to start disabled
   */
  public UserEventRuleEngine(CompiledRuleSet ruleSet) {
    current.set(ruleSet);
  }

  /**
   * Replaces the current rule set.
   *
   * @param ruleSet new rule set
   */
  public void update(CompiledRuleSet ruleSet) {
    current.set(ruleSet);
  }

  /**
   * Evaluates the exchange and returns matched rule results.
   *
   * @param exchange HTTP exchange context
   * @return list of matches; empty when no rules match or rule set empty
   */
  public List<RuleMatchResult> evaluate(HttpExchangeContext exchange) {
    CompiledRuleSet ruleSet = current.get();
    if (ruleSet == null || ruleSet.isEmpty()) {
      return List.of();
    }
    Objects.requireNonNull(exchange, "exchange");
    return ruleSet.evaluate(exchange, jsonSupport);
  }

  /**
   * Returns the rule defaults currently associated with the engine.
   *
   * @return rule defaults or {@code null} when no rule set is loaded
   */
  public RuleDefaults defaults() {
    CompiledRuleSet ruleSet = current.get();
    return ruleSet == null ? null : ruleSet.defaults();
  }

  public boolean hasRules() {
    CompiledRuleSet ruleSet = current.get();
    return ruleSet != null && !ruleSet.isEmpty();
  }

}
