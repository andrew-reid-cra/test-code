package ca.gc.cra.radar.application.events.rules;

import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.json.JsonSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executable rule set that applies compiled rules to HTTP exchanges.
 *
 * @since RADAR 1.1.0
 */
public final class CompiledRuleSet {
  private final RuleDefaults defaults;
  private final List<CompiledRule> rules;
  private final boolean requiresRequestBody;
  private final boolean requiresResponseBody;
  private final boolean requiresRequestJson;
  private final boolean requiresResponseJson;

  CompiledRuleSet(RuleDefaults defaults, List<CompiledRule> rules) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.rules = Objects.requireNonNull(rules, "rules");
    boolean reqBody = false;
    boolean rspBody = false;
    boolean reqJson = false;
    boolean rspJson = false;
    for (CompiledRule rule : rules) {
      reqBody |= rule.requiresRequestBody();
      rspBody |= rule.requiresResponseBody();
      reqJson |= rule.requiresRequestJson();
      rspJson |= rule.requiresResponseJson();
    }
    this.requiresRequestBody = reqBody;
    this.requiresResponseBody = rspBody;
    this.requiresRequestJson = reqJson;
    this.requiresResponseJson = rspJson;
  }

  RuleDefaults defaults() {
    return defaults;
  }

  boolean requiresRequestBody() {
    return requiresRequestBody;
  }

  boolean requiresResponseBody() {
    return requiresResponseBody;
  }

  boolean requiresRequestJson() {
    return requiresRequestJson;
  }

  boolean requiresResponseJson() {
    return requiresResponseJson;
  }

  boolean isEmpty() {
    return rules.isEmpty();
  }

  public int size() {
    return rules.size();
  }

  List<RuleMatchResult> evaluate(HttpExchangeContext exchange, JsonSupport jsonSupport) {
    Objects.requireNonNull(exchange, "exchange");
    JsonSupport parser = jsonSupport == null ? new JsonSupport() : jsonSupport;
    BodyContentCache bodies = new BodyContentCache(exchange, parser);
    List<RuleMatchResult> matches = new ArrayList<>();
    for (CompiledRule rule : rules) {
      rule.evaluate(exchange, defaults, bodies).ifPresent(matches::add);
    }
    return matches;
  }
}
