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

  /**
   * Returns the default values applied to events when individual rules omit fields.
   *
   * @return immutable defaults configured at compile time
   */
  RuleDefaults defaults() {
    return defaults;
  }

  /**
   * Indicates whether any rule needs access to the request body bytes.
   *
   * @return {@code true} when request payload extraction is required
   */
  boolean requiresRequestBody() {
    return requiresRequestBody;
  }

  /**
   * Indicates whether any rule depends on the response body.
   *
   * @return {@code true} when response payload extraction is required
   */
  boolean requiresResponseBody() {
    return requiresResponseBody;
  }

  /**
   * Signals that at least one rule requires the request body parsed as JSON.
   *
   * @return {@code true} when JSON decoding of the request is necessary
   */
  boolean requiresRequestJson() {
    return requiresRequestJson;
  }

  /**
   * Signals that at least one rule requires the response body parsed as JSON.
   *
   * @return {@code true} when JSON decoding of the response is necessary
   */
  boolean requiresResponseJson() {
    return requiresResponseJson;
  }

  /**
   * Indicates whether the rule set contains zero active rules.
   *
   * @return {@code true} for an empty rule set
   */
  boolean isEmpty() {
    return rules.isEmpty();
  }

  /**
   * Reports the number of compiled rules contained within this set.
   *
   * @return number of individual rules
   */
  public int size() {
    return rules.size();
  }

  /**
   * Evaluates every compiled rule against the supplied HTTP exchange.
   *
   * @param exchange reconstructed HTTP request/response pair to inspect
   * @param jsonSupport optional shared JSON support utility; a new instance is created when null
   * @return ordered list of rule match results
   */
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
