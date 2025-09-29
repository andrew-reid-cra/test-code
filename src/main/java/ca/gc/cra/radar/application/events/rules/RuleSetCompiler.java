package ca.gc.cra.radar.application.events.rules;

import ca.gc.cra.radar.application.events.json.JsonPathExpression;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compiles parsed rule definitions into executable rules.
 *
 * @since RADAR 1.1.0
 */
final class RuleSetCompiler {

  CompiledRuleSet compile(RuleDefaults defaults, List<RuleDefinition> definitions) {
    Objects.requireNonNull(defaults, "defaults");
    Objects.requireNonNull(definitions, "definitions");

    List<CompiledRule> compiled = new ArrayList<>(definitions.size());
    for (RuleDefinition definition : definitions) {
      compiled.add(compileRule(definition));
    }

    return new CompiledRuleSet(defaults, List.copyOf(compiled));
  }

  private CompiledRule compileRule(RuleDefinition definition) {
    MatchDefinition match = definition.match();

    CompiledRule.MethodMatcher methodMatcher = compileMethod(match.method());
    CompiledRule.PathMatcher pathMatcher = compilePath(match.path());
    CompiledRule.StatusMatcher statusMatcher = compileStatus(match.status());

    List<CompiledRule.FieldMatcher> headers = compileFieldMatchers(match.headers(), CompiledRule.FieldSource.REQUEST_HEADER);
    List<CompiledRule.FieldMatcher> cookies = compileFieldMatchers(match.cookies(), CompiledRule.FieldSource.REQUEST_COOKIE);
    List<CompiledRule.FieldMatcher> query = compileFieldMatchers(match.query(), CompiledRule.FieldSource.QUERY_PARAMETER);
    List<CompiledRule.FieldMatcher> responseHeaders = compileFieldMatchers(match.responseHeaders(), CompiledRule.FieldSource.RESPONSE_HEADER);

    List<CompiledRule.BodyMatcher> bodies = compileBodies(match.bodies());

    CompiledRule.ExtractionPlan extractionPlan = compileExtraction(definition.extract());

    CompiledRule.EventTemplate eventTemplate = new CompiledRule.EventTemplate(
        definition.event().type(),
        definition.event().attributes());

    return new CompiledRule(
        definition.id(),
        definition.description(),
        methodMatcher,
        pathMatcher,
        statusMatcher,
        headers,
        cookies,
        query,
        responseHeaders,
        bodies,
        extractionPlan,
        eventTemplate);
  }

  private CompiledRule.MethodMatcher compileMethod(MethodConditionDefinition definition) {
    if (definition == null || definition.methods() == null || definition.methods().isEmpty()) {
      return null;
    }
    Set<String> methods = definition.methods().stream()
        .filter(Objects::nonNull)
        .map(s -> s.trim().toUpperCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
    return new CompiledRule.MethodMatcher(methods);
  }

  private CompiledRule.PathMatcher compilePath(PathConditionDefinition definition) {
    if (definition == null) {
      return null;
    }
    String equals = normalize(definition.equals());
    String prefix = normalize(definition.prefix());
    String contains = normalize(definition.contains());
    Pattern regex = null;
    List<String> groups = List.of();
    if (definition.regex() != null) {
      PatternWithGroups compiled = compileRegex(definition.regex());
      regex = compiled.pattern();
      groups = compiled.groupNames();
    }
    return new CompiledRule.PathMatcher(equals, prefix, contains, regex, groups);
  }

  private CompiledRule.StatusMatcher compileStatus(StatusConditionDefinition definition) {
    if (definition == null || definition.statuses() == null || definition.statuses().isEmpty()) {
      return null;
    }
    Set<Integer> statuses = definition.statuses().stream().collect(Collectors.toUnmodifiableSet());
    return new CompiledRule.StatusMatcher(statuses);
  }

  private List<CompiledRule.FieldMatcher> compileFieldMatchers(
      Map<String, FieldConditionDefinition> definitions,
      CompiledRule.FieldSource source) {
    if (definitions == null || definitions.isEmpty()) {
      return List.of();
    }
    List<CompiledRule.FieldMatcher> matchers = new ArrayList<>(definitions.size());
    for (Map.Entry<String, FieldConditionDefinition> entry : definitions.entrySet()) {
      String key = entry.getKey();
      FieldConditionDefinition conditionDef = entry.getValue();
      CompiledRule.FieldCondition condition = compileFieldCondition(conditionDef);
      matchers.add(new CompiledRule.FieldMatcher(key, source, condition));
    }
    return List.copyOf(matchers);
  }

  private CompiledRule.FieldCondition compileFieldCondition(FieldConditionDefinition definition) {
    String equals = normalize(definition.equals());
    String contains = normalize(definition.contains());
    Pattern regex = definition.regex() == null ? null : Pattern.compile(definition.regex());
    return new CompiledRule.FieldCondition(equals, contains, regex);
  }

  private List<CompiledRule.BodyMatcher> compileBodies(List<BodyConditionDefinition> definitions) {
    if (definitions == null || definitions.isEmpty()) {
      return List.of();
    }
    List<CompiledRule.BodyMatcher> matchers = new ArrayList<>(definitions.size());
    for (BodyConditionDefinition definition : definitions) {
      CompiledRule.BodyTarget target = definition.target() == null
          ? CompiledRule.BodyTarget.RESPONSE
          : CompiledRule.BodyTarget.valueOf(definition.target().name());
      String contains = normalize(definition.contains());
      Pattern regex = definition.regex() == null ? null : Pattern.compile(definition.regex());
      List<CompiledRule.JsonPredicate> predicates = compileJsonPredicates(definition.jsonPredicates(), target);
      matchers.add(new CompiledRule.BodyMatcher(target, contains, regex, predicates));
    }
    return List.copyOf(matchers);
  }

  private List<CompiledRule.JsonPredicate> compileJsonPredicates(
      List<JsonPredicateDefinition> definitions,
      CompiledRule.BodyTarget defaultTarget) {
    if (definitions == null || definitions.isEmpty()) {
      return List.of();
    }
    List<CompiledRule.JsonPredicate> predicates = new ArrayList<>(definitions.size());
    for (JsonPredicateDefinition definition : definitions) {
      JsonPathExpression path = JsonPathExpression.compile(definition.path());
      String equals = normalize(definition.equals());
      Pattern regex = definition.regex() == null ? null : Pattern.compile(definition.regex());
      Boolean exists = definition.exists();
      CompiledRule.BodyTarget target = defaultTarget;
      predicates.add(new CompiledRule.JsonPredicate(target, path, equals, regex, exists));
    }
    return List.copyOf(predicates);
  }

  private CompiledRule.ExtractionPlan compileExtraction(ExtractDefinition definition) {
    if (definition == null) {
      return null;
    }
    CompiledRule.ValueExtractor user = compileValueExtractor(definition.userId());
    Map<String, CompiledRule.ValueExtractor> extras = new LinkedHashMap<>();
    if (definition.extras() != null) {
      for (Map.Entry<String, ValueExpressionDefinition> entry : definition.extras().entrySet()) {
        extras.put(entry.getKey(), compileValueExtractor(entry.getValue()));
      }
    }
    return new CompiledRule.ExtractionPlan(user, Map.copyOf(extras));
  }

  private CompiledRule.ValueExtractor compileValueExtractor(ValueExpressionDefinition definition) {
    if (definition == null) {
      return null;
    }
    ValueSource source = definition.source();
    String identifier = definition.identifier();
    if (identifier != null) {
      identifier = identifier.trim();
    }
    String literal = definition.literal();
    if (literal != null) {
      literal = literal.trim();
    }
    JsonPathExpression jsonPath = definition.jsonPath() == null ? null : JsonPathExpression.compile(definition.jsonPath());
    return new CompiledRule.ValueExtractor(source, identifier, literal, jsonPath);
  }

  private String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private PatternWithGroups compileRegex(String expression) {
    StringBuilder converted = new StringBuilder();
    Matcher pythonStyle = PYTHON_GROUP.matcher(expression);
    int index = 0;
    while (pythonStyle.find()) {
      converted.append(expression, index, pythonStyle.start());
      String name = pythonStyle.group(1);
      converted.append("(?<").append(name).append(">");
      index = pythonStyle.end();
    }
    converted.append(expression.substring(index));
    String javaRegex = converted.toString();
    Pattern pattern = Pattern.compile(javaRegex);

    List<String> names = new ArrayList<>();
    Matcher javaStyle = JAVA_GROUP.matcher(javaRegex);
    while (javaStyle.find()) {
      names.add(javaStyle.group(1));
    }
    return new PatternWithGroups(pattern, List.copyOf(names));
  }

  private static final Pattern PYTHON_GROUP = Pattern.compile("\\(\\?P<([A-Za-z][A-Za-z0-9_]*)>");
  private static final Pattern JAVA_GROUP = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9_]*)>");

  private record PatternWithGroups(Pattern pattern, List<String> groupNames) {}
}
