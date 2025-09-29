package ca.gc.cra.radar.application.events.rules;

import java.util.List;
import java.util.Map;

/**
 * Raw rule definition parsed from YAML prior to compilation.
 *
 * @since RADAR 1.1.0
 */
record RuleDefinition(
    String id,
    String description,
    MatchDefinition match,
    ExtractDefinition extract,
    EventDefinition event) {}

record MatchDefinition(
    MethodConditionDefinition method,
    PathConditionDefinition path,
    StatusConditionDefinition status,
    Map<String, FieldConditionDefinition> headers,
    Map<String, FieldConditionDefinition> cookies,
    Map<String, FieldConditionDefinition> query,
    Map<String, FieldConditionDefinition> responseHeaders,
    List<BodyConditionDefinition> bodies) {}

record MethodConditionDefinition(List<String> methods) {}

record PathConditionDefinition(String equals, String prefix, String contains, String regex) {}

record StatusConditionDefinition(List<Integer> statuses) {}

record FieldConditionDefinition(String equals, String contains, String regex) {}

record BodyConditionDefinition(
    BodyTarget target,
    String contains,
    String regex,
    List<JsonPredicateDefinition> jsonPredicates) {}

enum BodyTarget {
  REQUEST,
  RESPONSE
}

record JsonPredicateDefinition(
    String path,
    String equals,
    String regex,
    Boolean exists) {}

record ExtractDefinition(ValueExpressionDefinition userId, Map<String, ValueExpressionDefinition> extras) {}

record ValueExpressionDefinition(ValueSource source, String identifier, String literal, String jsonPath, BodyTarget target) {}

enum ValueSource {
  PATH_PARAMETER,
  REQUEST_HEADER,
  RESPONSE_HEADER,
  REQUEST_COOKIE,
  QUERY_PARAMETER,
  REQUEST_JSON_PATH,
  RESPONSE_JSON_PATH,
  STATIC
}

record EventDefinition(String type, Map<String, String> attributes) {}
