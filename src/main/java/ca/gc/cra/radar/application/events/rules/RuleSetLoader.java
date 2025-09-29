package ca.gc.cra.radar.application.events.rules;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Loads user event rules from YAML files into intermediate definitions.
 *
 * @since RADAR 1.1.0
 */
final class RuleSetLoader {
  LoadedRuleSet load(List<Path> sources) throws IOException {
    Objects.requireNonNull(sources, "sources");
    RuleDefaults defaults = RuleDefaults.empty();
    List<RuleDefinition> rules = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();

    for (Path path : sources) {
      Document document = parseDocument(path);
      defaults = mergeDefaults(defaults, document.defaults());
      for (RuleDefinition rule : document.rules()) {
        if (!ids.add(rule.id())) {
          throw new IllegalArgumentException("Duplicate rule id detected: " + rule.id());
        }
        rules.add(rule);
      }
    }

    return new LoadedRuleSet(defaults, List.copyOf(rules));
  }

  private Document parseDocument(Path path) throws IOException {
    if (!Files.exists(path)) {
      throw new IOException("Rule file not found: " + path);
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      Object rootObj = new Yaml().load(reader);
      if (rootObj == null) {
        return new Document(RuleDefaults.empty(), List.of());
      }
      Map<String, Object> root = asMap(rootObj, "root");

      Object versionNode = root.get("version");
      int version = toInt(versionNode, "version");
      if (version != 1) {
        throw new IllegalArgumentException("Unsupported rule version " + version + " in " + path);
      }

      RuleDefaults defaults = RuleDefaults.empty();
      Object defaultsNode = root.get("defaults");
      if (defaultsNode != null) {
        defaults = parseDefaults(asMap(defaultsNode, "defaults"));
      }

      List<RuleDefinition> definitions = new ArrayList<>();
      Object rulesNode = root.get("rules");
      if (rulesNode instanceof Iterable<?> iterable) {
        for (Object ruleNode : iterable) {
          RuleDefinition definition = parseRule(asMap(ruleNode, "rule"));
          definitions.add(definition);
        }
      }

      return new Document(defaults, List.copyOf(definitions));
    } catch (YAMLException ex) {
      throw new IllegalArgumentException("Failed to parse YAML rules at " + path, ex);
    }
  }

  private RuleDefaults mergeDefaults(RuleDefaults base, RuleDefaults override) {
    String app = override.app().isBlank() ? base.app() : override.app();
    String source = override.source().isBlank() ? base.source() : override.source();
    Map<String, String> attributes = new LinkedHashMap<>(base.attributes());
    attributes.putAll(override.attributes());
    return new RuleDefaults(app, source, attributes);
  }

  private RuleDefaults parseDefaults(Map<String, Object> map) {
    String app = toString(map.getOrDefault("app", ""));
    String source = toString(map.getOrDefault("source", ""));
    Map<String, String> attributes = parseStringMap(map.get("attributes"));
    return new RuleDefaults(app, source, attributes);
  }

  private RuleDefinition parseRule(Map<String, Object> map) {
    String id = requireString(map, "id");
    String description = toOptionalString(map.get("description"));

    MatchDefinition match = parseMatch(asMapOrEmpty(map.get("match"), "match"));
    ExtractDefinition extract = parseExtract(map.get("extract"));
    EventDefinition event = parseEvent(asMap(map.get("event"), "event"));

    return new RuleDefinition(id, description, match, extract, event);
  }

  private MatchDefinition parseMatch(Map<String, Object> map) {
    MethodConditionDefinition method = parseMethod(map.get("method"));
    PathConditionDefinition path = parsePath(map.get("path"));
    StatusConditionDefinition status = parseStatus(map.get("status"));
    Map<String, FieldConditionDefinition> headers = parseFieldConditions(map.get("headers"));
    Map<String, FieldConditionDefinition> cookies = parseFieldConditions(map.get("cookies"));
    Map<String, FieldConditionDefinition> query = parseFieldConditions(map.get("query"));
    Map<String, FieldConditionDefinition> responseHeaders = parseFieldConditions(map.get("responseHeaders"));

    List<BodyConditionDefinition> bodies = new ArrayList<>();
    Object bodyNode = map.get("body");
    if (bodyNode != null) {
      bodies.add(parseBody(bodyNode, BodyTarget.RESPONSE));
    }
    Object requestBodyNode = map.get("requestBody");
    if (requestBodyNode != null) {
      bodies.add(parseBody(requestBodyNode, BodyTarget.REQUEST));
    }
    Object responseBodyNode = map.get("responseBody");
    if (responseBodyNode != null) {
      bodies.add(parseBody(responseBodyNode, BodyTarget.RESPONSE));
    }

    return new MatchDefinition(method, path, status, headers, cookies, query, responseHeaders, List.copyOf(bodies));
  }

  private MethodConditionDefinition parseMethod(Object node) {
    if (node == null) {
      return null;
    }
    List<String> methods = new ArrayList<>();
    if (node instanceof String single) {
      methods.add(single.strip().toUpperCase(Locale.ROOT));
    } else if (node instanceof Iterable<?> iterable) {
      for (Object value : iterable) {
        methods.add(toString(value).strip().toUpperCase(Locale.ROOT));
      }
    } else {
      throw new IllegalArgumentException("method must be string or list");
    }
    return new MethodConditionDefinition(List.copyOf(methods));
  }

  private PathConditionDefinition parsePath(Object node) {
    if (node == null) {
      return null;
    }
    Map<String, Object> map = asMap(node, "path");
    return new PathConditionDefinition(
        toOptionalString(map.get("equals")),
        toOptionalString(map.get("prefix")),
        toOptionalString(map.get("contains")),
        toOptionalString(map.get("regex")));
  }

  private StatusConditionDefinition parseStatus(Object node) {
    if (node == null) {
      return null;
    }
    List<Integer> statuses = new ArrayList<>();
    if (node instanceof Number number) {
      statuses.add(number.intValue());
    } else if (node instanceof Map<?, ?> map) {
      Object in = map.get("in");
      if (in instanceof Iterable<?> iterable) {
        for (Object value : iterable) {
          statuses.add(toInt(value, "status"));
        }
      } else {
        throw new IllegalArgumentException("status.in must be a list");
      }
    } else {
      throw new IllegalArgumentException("status must be integer or mapping");
    }
    return new StatusConditionDefinition(List.copyOf(statuses));
  }

  private Map<String, FieldConditionDefinition> parseFieldConditions(Object node) {
    if (node == null) {
      return Map.of();
    }
    Map<String, Object> map = asMap(node, "fields");
    Map<String, FieldConditionDefinition> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      FieldConditionDefinition condition;
      if (value instanceof Map<?, ?> nested) {
        Map<String, Object> nestedMap = asMap(nested, key);
        condition = new FieldConditionDefinition(
            toOptionalString(nestedMap.get("equals")),
            toOptionalString(nestedMap.get("contains")),
            toOptionalString(nestedMap.get("regex")));
      } else {
        condition = new FieldConditionDefinition(toString(value), null, null);
      }
      result.put(key, condition);
    }
    return Map.copyOf(result);
  }

  private BodyConditionDefinition parseBody(Object node, BodyTarget defaultTarget) {
    Map<String, Object> map = asMap(node, "body");
    BodyTarget target = parseBodyTarget(map.get("target"), defaultTarget);
    String contains = toOptionalString(map.get("contains"));
    String regex = toOptionalString(map.get("regex"));
    List<JsonPredicateDefinition> predicates = parseJsonPredicates(map.get("jsonpath"));
    return new BodyConditionDefinition(target, contains, regex, predicates);
  }

  private BodyTarget parseBodyTarget(Object node, BodyTarget fallback) {
    if (node == null) {
      return fallback;
    }
    String value = toString(node).trim().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "request", "req" -> BodyTarget.REQUEST;
      case "response", "resp" -> BodyTarget.RESPONSE;
      default -> throw new IllegalArgumentException("Unsupported body target: " + node);
    };
  }

  private List<JsonPredicateDefinition> parseJsonPredicates(Object node) {
    if (node == null) {
      return List.of();
    }
    List<JsonPredicateDefinition> predicates = new ArrayList<>();
    if (node instanceof Iterable<?> iterable) {
      for (Object element : iterable) {
        predicates.add(parseJsonPredicate(element));
      }
    } else {
      predicates.add(parseJsonPredicate(node));
    }
    return List.copyOf(predicates);
  }

  private JsonPredicateDefinition parseJsonPredicate(Object node) {
    Map<String, Object> map = asMap(node, "jsonpath predicate");
    String path = requireString(map, "path");
    String equals = toOptionalString(map.get("equals"));
    String regex = toOptionalString(map.get("regex"));
    Boolean exists = map.containsKey("exists") ? toBoolean(map.get("exists"), "exists") : null;
    return new JsonPredicateDefinition(path, equals, regex, exists);
  }

  private ExtractDefinition parseExtract(Object node) {
    if (node == null) {
      return new ExtractDefinition(null, Map.of());
    }
    Map<String, Object> map = asMap(node, "extract");
    ValueExpressionDefinition userId = parseValueExpression(map.get("user_id"), BodyTarget.RESPONSE);

    Map<String, ValueExpressionDefinition> extras = new LinkedHashMap<>();
    Object extraNode = map.get("extra");
    if (extraNode instanceof Map<?, ?> extraMap) {
      for (Map.Entry<?, ?> entry : extraMap.entrySet()) {
        Object keyObj = entry.getKey();
        if (!(keyObj instanceof String key)) {
          throw new IllegalArgumentException("extract.extra keys must be strings");
        }
        ValueExpressionDefinition value = parseValueExpression(entry.getValue(), BodyTarget.RESPONSE);
        extras.put(key, value);
      }
    }
    return new ExtractDefinition(userId, Map.copyOf(extras));
  }

  private ValueExpressionDefinition parseValueExpression(Object node, BodyTarget defaultBodyTarget) {
    if (node == null) {
      return null;
    }
    if (node instanceof String str) {
      return parseValueExpressionString(str);
    }
    Map<String, Object> map = asMap(node, "value expression");
    BodyTarget target = parseBodyTarget(map.get("target"), defaultBodyTarget);
    if (map.containsKey("literal")) {
      return new ValueExpressionDefinition(ValueSource.STATIC, null, toString(map.get("literal")), null, target);
    }
    if (map.containsKey("path")) {
      return new ValueExpressionDefinition(ValueSource.PATH_PARAMETER, toString(map.get("path")), null, null, target);
    }
    if (map.containsKey("header")) {
      return new ValueExpressionDefinition(ValueSource.REQUEST_HEADER, toString(map.get("header")), null, null, target);
    }
    if (map.containsKey("responseHeader")) {
      return new ValueExpressionDefinition(ValueSource.RESPONSE_HEADER, toString(map.get("responseHeader")), null, null, target);
    }
    if (map.containsKey("cookie")) {
      return new ValueExpressionDefinition(ValueSource.REQUEST_COOKIE, toString(map.get("cookie")), null, null, target);
    }
    if (map.containsKey("query")) {
      return new ValueExpressionDefinition(ValueSource.QUERY_PARAMETER, toString(map.get("query")), null, null, target);
    }
    if (map.containsKey("jsonpath")) {
      String jsonPath = toString(map.get("jsonpath"));
      ValueSource source = target == BodyTarget.REQUEST ? ValueSource.REQUEST_JSON_PATH : ValueSource.RESPONSE_JSON_PATH;
      return new ValueExpressionDefinition(source, null, null, jsonPath, target);
    }
    throw new IllegalArgumentException("Unsupported extract mapping: " + map.keySet());
  }

  private ValueExpressionDefinition parseValueExpressionString(String expression) {
    String trimmed = expression.trim();
    if (trimmed.startsWith("path.")) {
      return new ValueExpressionDefinition(ValueSource.PATH_PARAMETER, trimmed.substring(5), null, null, BodyTarget.RESPONSE);
    }
    if (trimmed.startsWith("header.")) {
      return new ValueExpressionDefinition(ValueSource.REQUEST_HEADER, trimmed.substring(7), null, null, BodyTarget.RESPONSE);
    }
    if (trimmed.startsWith("response.header.")) {
      return new ValueExpressionDefinition(ValueSource.RESPONSE_HEADER, trimmed.substring(16), null, null, BodyTarget.RESPONSE);
    }
    if (trimmed.startsWith("cookie.")) {
      return new ValueExpressionDefinition(ValueSource.REQUEST_COOKIE, trimmed.substring(7), null, null, BodyTarget.RESPONSE);
    }
    if (trimmed.startsWith("query.")) {
      return new ValueExpressionDefinition(ValueSource.QUERY_PARAMETER, trimmed.substring(6), null, null, BodyTarget.RESPONSE);
    }
    return new ValueExpressionDefinition(ValueSource.STATIC, null, trimmed, null, BodyTarget.RESPONSE);
  }

  private EventDefinition parseEvent(Map<String, Object> map) {
    String type = requireString(map, "type");
    Map<String, String> attributes = parseStringMap(map.get("attributes"));
    return new EventDefinition(type, attributes);
  }

  private Map<String, Object> asMap(Object node, String context) {
    if (node == null) {
      throw new IllegalArgumentException(context + " section is missing");
    }
    if (!(node instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException(context + " must be a mapping");
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      Object keyObj = entry.getKey();
      if (!(keyObj instanceof String key)) {
        throw new IllegalArgumentException(context + " contains non-string key");
      }
      map.put(key, entry.getValue());
    }
    return map;
  }

  private Map<String, Object> asMapOrEmpty(Object node, String context) {
    return node == null ? Map.of() : asMap(node, context);
  }

  private String requireString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing required field: " + key);
    }
    return toString(value);
  }

  private String toOptionalString(Object value) {
    if (value == null) {
      return null;
    }
    String str = toString(value);
    return str.isBlank() ? null : str;
  }

  private String toString(Object value) {
    if (value == null) {
      return "";
    }
    return value.toString();
  }

  private int toInt(Object value, String context) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String str && !str.isBlank()) {
      try {
        return Integer.parseInt(str.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Invalid integer for " + context + ": '" + str + "'");
      }
    }
    throw new IllegalArgumentException("Invalid integer for " + context + ": " + value);
  }

  private boolean toBoolean(Object value, String context) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String str) {
      return Boolean.parseBoolean(str.trim());
    }
    throw new IllegalArgumentException("Invalid boolean for " + context + ": " + value);
  }

  private Map<String, String> parseStringMap(Object node) {
    if (node == null) {
      return Map.of();
    }
    Map<String, Object> map = asMap(node, "attributes");
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      result.put(entry.getKey(), toString(entry.getValue()));
    }
    return Map.copyOf(result);
  }

  private record Document(RuleDefaults defaults, List<RuleDefinition> rules) {}

  record LoadedRuleSet(RuleDefaults defaults, List<RuleDefinition> rules) {}
}
