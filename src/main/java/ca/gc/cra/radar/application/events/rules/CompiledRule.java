package ca.gc.cra.radar.application.events.rules;

import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.http.HttpRequestContext;
import ca.gc.cra.radar.application.events.http.HttpResponseContext;
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

/**
 * Compiled user event rule with pre-built matchers and extraction plan.
 *
 * @since RADAR 1.1.0
 */
final class CompiledRule {
  private final String id;
  private final String description;
  private final MethodMatcher methodMatcher;
  private final PathMatcher pathMatcher;
  private final StatusMatcher statusMatcher;
  private final List<FieldMatcher> headerMatchers;
  private final List<FieldMatcher> cookieMatchers;
  private final List<FieldMatcher> queryMatchers;
  private final List<FieldMatcher> responseHeaderMatchers;
  private final List<BodyMatcher> bodyMatchers;
  private final ExtractionPlan extractionPlan;
  private final EventTemplate eventTemplate;
  private final boolean requiresRequestBody;
  private final boolean requiresResponseBody;
  private final boolean requiresRequestJson;
  private final boolean requiresResponseJson;

  CompiledRule(
      String id,
      String description,
      MethodMatcher methodMatcher,
      PathMatcher pathMatcher,
      StatusMatcher statusMatcher,
      List<FieldMatcher> headerMatchers,
      List<FieldMatcher> cookieMatchers,
      List<FieldMatcher> queryMatchers,
      List<FieldMatcher> responseHeaderMatchers,
      List<BodyMatcher> bodyMatchers,
      ExtractionPlan extractionPlan,
      EventTemplate eventTemplate) {
    this.id = Objects.requireNonNull(id, "id");
    this.description = description;
    this.methodMatcher = methodMatcher;
    this.pathMatcher = pathMatcher;
    this.statusMatcher = statusMatcher;
    this.headerMatchers = headerMatchers;
    this.cookieMatchers = cookieMatchers;
    this.queryMatchers = queryMatchers;
    this.responseHeaderMatchers = responseHeaderMatchers;
    this.bodyMatchers = bodyMatchers;
    this.extractionPlan = extractionPlan;
    this.eventTemplate = Objects.requireNonNull(eventTemplate, "eventTemplate");
    this.requiresRequestBody = bodyMatchers.stream().anyMatch(b -> b.target == BodyTarget.REQUEST && b.requiresBody)
        || (extractionPlan != null && extractionPlan.requiresRequestBody());
    this.requiresResponseBody = bodyMatchers.stream().anyMatch(b -> b.target == BodyTarget.RESPONSE && b.requiresBody)
        || (extractionPlan != null && extractionPlan.requiresResponseBody());
    this.requiresRequestJson = bodyMatchers.stream().anyMatch(b -> b.target == BodyTarget.REQUEST && b.requiresJson)
        || (extractionPlan != null && extractionPlan.requiresRequestJson());
    this.requiresResponseJson = bodyMatchers.stream().anyMatch(b -> b.target == BodyTarget.RESPONSE && b.requiresJson)
        || (extractionPlan != null && extractionPlan.requiresResponseJson());
  }

  Optional<RuleMatchResult> evaluate(HttpExchangeContext exchange, RuleDefaults defaults, BodyContentCache bodies) {
    HttpRequestContext request = exchange.request();
    if (methodMatcher != null && !methodMatcher.matches(request.method())) {
      return Optional.empty();
    }

    Map<String, String> pathParams = new LinkedHashMap<>();
    if (pathMatcher != null && !pathMatcher.matches(request.path(), pathParams)) {
      return Optional.empty();
    }

    HttpResponseContext response = exchange.response();
    if (statusMatcher != null && !statusMatcher.matches(response)) {
      return Optional.empty();
    }

    if (!matchAll(headerMatchers, request, response)) {
      return Optional.empty();
    }
    if (!matchAll(cookieMatchers, request, response)) {
      return Optional.empty();
    }
    if (!matchAll(queryMatchers, request, response)) {
      return Optional.empty();
    }
    if (!matchAll(responseHeaderMatchers, request, response)) {
      return Optional.empty();
    }

    for (BodyMatcher matcher : bodyMatchers) {
      if (!matcher.matches(exchange, bodies)) {
        return Optional.empty();
      }
    }

    ExtractionResult extraction = extractionPlan == null
        ? ExtractionResult.EMPTY
        : extractionPlan.extract(exchange, pathParams, bodies);

    Map<String, String> attributes = new LinkedHashMap<>(defaults.attributes());
    attributes.putAll(eventTemplate.attributes);
    attributes.putAll(extraction.attributes());

    return Optional.of(new RuleMatchResult(
        id,
        description,
        eventTemplate.type,
        extraction.userId(),
        attributes));
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

  private boolean matchAll(List<FieldMatcher> matchers, HttpRequestContext request, HttpResponseContext response) {
    for (FieldMatcher matcher : matchers) {
      if (!matcher.matches(request, response)) {
        return false;
      }
    }
    return true;
  }

  enum FieldSource {
    REQUEST_HEADER,
    RESPONSE_HEADER,
    REQUEST_COOKIE,
    QUERY_PARAMETER
  }

  enum BodyTarget {
    REQUEST,
    RESPONSE
  }

  static final class MethodMatcher {
    private final Set<String> methods;

    MethodMatcher(Set<String> methods) {
      this.methods = methods;
    }

    boolean matches(String method) {
      return methods == null || methods.isEmpty() || methods.contains(method.toUpperCase(Locale.ROOT));
    }
  }

  static final class PathMatcher {
    private final String equals;
    private final String prefix;
    private final String contains;
    private final Pattern regex;
    private final List<String> groupNames;

    PathMatcher(String equals, String prefix, String contains, Pattern regex, List<String> groupNames) {
      this.equals = equals;
      this.prefix = prefix;
      this.contains = contains;
      this.regex = regex;
      this.groupNames = groupNames;
    }

    boolean matches(String path, Map<String, String> outputParams) {
      if (equals != null && !Objects.equals(path, equals)) {
        return false;
      }
      if (prefix != null && !path.startsWith(prefix)) {
        return false;
      }
      if (contains != null && !path.contains(contains)) {
        return false;
      }
      if (regex != null) {
        Matcher matcher = regex.matcher(path);
        if (!matcher.matches()) {
          return false;
        }
        if (!groupNames.isEmpty()) {
          for (String name : groupNames) {
            String value = matcher.group(name);
            if (value != null && !value.isEmpty()) {
              outputParams.put(name, value);
            }
          }
        }
      }
      return true;
    }
  }

  static final class StatusMatcher {
    private final Set<Integer> statuses;

    StatusMatcher(Set<Integer> statuses) {
      this.statuses = statuses;
    }

    boolean matches(HttpResponseContext response) {
      if (statuses == null || statuses.isEmpty()) {
        return true;
      }
      if (response == null) {
        return false;
      }
      return statuses.contains(response.status());
    }
  }

  static final class FieldMatcher {
    private final String key;
    private final FieldSource source;
    private final FieldCondition condition;

    FieldMatcher(String key, FieldSource source, FieldCondition condition) {
      this.key = key;
      this.source = source;
      this.condition = condition;
    }

    boolean matches(HttpRequestContext request, HttpResponseContext response) {
      String value = switch (source) {
        case REQUEST_HEADER -> request.header(key).orElse(null);
        case RESPONSE_HEADER -> response == null ? null : response.header(key).orElse(null);
        case REQUEST_COOKIE -> request.cookie(key).orElse(null);
        case QUERY_PARAMETER -> request.queryParam(key).orElse(null);
      };
      if (value == null) {
        return false;
      }
      return condition.matches(value);
    }
  }

  static final class FieldCondition {
    private final String equals;
    private final String contains;
    private final Pattern regex;

    FieldCondition(String equals, String contains, Pattern regex) {
      this.equals = equals;
      this.contains = contains;
      this.regex = regex;
    }

    boolean matches(String value) {
      if (equals != null && !Objects.equals(value, equals)) {
        return false;
      }
      if (contains != null && !value.contains(contains)) {
        return false;
      }
      if (regex != null && !regex.matcher(value).find()) {
        return false;
      }
      return true;
    }
  }

  static final class BodyMatcher {
    private final BodyTarget target;
    private final String contains;
    private final Pattern regex;
    private final List<JsonPredicate> jsonPredicates;
    private final boolean requiresBody;
    private final boolean requiresJson;

    BodyMatcher(BodyTarget target, String contains, Pattern regex, List<JsonPredicate> jsonPredicates) {
      this.target = target;
      this.contains = contains;
      this.regex = regex;
      this.jsonPredicates = jsonPredicates;
      this.requiresBody = contains != null || regex != null;
      this.requiresJson = jsonPredicates.stream().anyMatch(JsonPredicate::requiresJson);
    }

    boolean matches(HttpExchangeContext exchange, BodyContentCache bodies) {
      if (contains != null || regex != null) {
        Optional<String> body = target == BodyTarget.REQUEST
            ? bodies.requestBodyString()
            : bodies.responseBodyString();
        if (body.isEmpty()) {
          return false;
        }
        String text = body.get();
        if (contains != null && !text.contains(contains)) {
          return false;
        }
        if (regex != null && !regex.matcher(text).find()) {
          return false;
        }
      }
      for (JsonPredicate predicate : jsonPredicates) {
        if (!predicate.matches(target, bodies)) {
          return false;
        }
      }
      return true;
    }
  }

  static final class JsonPredicate {
    private final BodyTarget target;
    private final JsonPathExpression path;
    private final String equals;
    private final Pattern regex;
    private final Boolean exists;

    JsonPredicate(BodyTarget target, JsonPathExpression path, String equals, Pattern regex, Boolean exists) {
      this.target = target;
      this.path = path;
      this.equals = equals;
      this.regex = regex;
      this.exists = exists;
    }

    boolean requiresJson() {
      return true;
    }

    boolean matches(BodyTarget defaultTarget, BodyContentCache bodies) {
      BodyTarget evaluationTarget = target != null ? target : defaultTarget;
      Optional<Object> json = evaluationTarget == BodyTarget.REQUEST
          ? bodies.requestJson()
          : bodies.responseJson();
      Optional<Object> value = json.flatMap(path::read);
      if (Boolean.TRUE.equals(exists)) {
        if (value.isEmpty() || value.get() == null) {
          return false;
        }
      }
      if (equals != null) {
        if (value.isEmpty() || !Objects.equals(stringify(value.get()), equals)) {
          return false;
        }
      }
      if (regex != null) {
        if (value.isEmpty() || !regex.matcher(stringify(value.get())).find()) {
          return false;
        }
      }
      if (Boolean.FALSE.equals(exists) && value.isPresent() && value.get() != null) {
        return false;
      }
      return true;
    }

    private String stringify(Object value) {
      return value instanceof String str ? str : String.valueOf(value);
    }
  }

  static final class ExtractionPlan {
    private final ValueExtractor userIdExtractor;
    private final Map<String, ValueExtractor> extras;

    ExtractionPlan(ValueExtractor userIdExtractor, Map<String, ValueExtractor> extras) {
      this.userIdExtractor = userIdExtractor;
      this.extras = extras;
    }

    ExtractionResult extract(HttpExchangeContext exchange, Map<String, String> pathParams, BodyContentCache bodies) {
      String userId = userIdExtractor == null
          ? null
          : userIdExtractor.extract(exchange, pathParams, bodies).orElse(null);
      Map<String, String> attributes = new LinkedHashMap<>();
      for (Map.Entry<String, ValueExtractor> entry : extras.entrySet()) {
        entry.getValue().extract(exchange, pathParams, bodies).ifPresent(value -> attributes.put(entry.getKey(), value));
      }
      return new ExtractionResult(userId, attributes);
    }

    boolean requiresRequestBody() {
      if (userIdExtractor != null && userIdExtractor.requiresRequestBody()) {
        return true;
      }
      for (ValueExtractor extractor : extras.values()) {
        if (extractor.requiresRequestBody()) {
          return true;
        }
      }
      return false;
    }

    boolean requiresResponseBody() {
      if (userIdExtractor != null && userIdExtractor.requiresResponseBody()) {
        return true;
      }
      for (ValueExtractor extractor : extras.values()) {
        if (extractor.requiresResponseBody()) {
          return true;
        }
      }
      return false;
    }

    boolean requiresRequestJson() {
      if (userIdExtractor != null && userIdExtractor.requiresRequestJson()) {
        return true;
      }
      for (ValueExtractor extractor : extras.values()) {
        if (extractor.requiresRequestJson()) {
          return true;
        }
      }
      return false;
    }

    boolean requiresResponseJson() {
      if (userIdExtractor != null && userIdExtractor.requiresResponseJson()) {
        return true;
      }
      for (ValueExtractor extractor : extras.values()) {
        if (extractor.requiresResponseJson()) {
          return true;
        }
      }
      return false;
    }
  }

  static final class ValueExtractor {
    private final ValueSource source;
    private final String identifier;
    private final String literal;
    private final JsonPathExpression jsonPath;

    ValueExtractor(ValueSource source, String identifier, String literal, JsonPathExpression jsonPath) {
      this.source = source;
      this.identifier = identifier;
      this.literal = literal;
      this.jsonPath = jsonPath;
    }

    Optional<String> extract(HttpExchangeContext exchange, Map<String, String> pathParams, BodyContentCache bodies) {
      return switch (source) {
        case PATH_PARAMETER -> normalizeValue(pathParams.get(identifier));
        case REQUEST_HEADER -> normalizeValue(exchange.request().header(identifier).orElse(null));
        case RESPONSE_HEADER -> {
          HttpResponseContext response = exchange.response();
          yield response == null ? Optional.empty() : normalizeValue(response.header(identifier).orElse(null));
        }
        case REQUEST_COOKIE -> normalizeValue(exchange.request().cookie(identifier).orElse(null));
        case QUERY_PARAMETER -> normalizeValue(exchange.request().queryParam(identifier).orElse(null));
        case REQUEST_JSON_PATH -> bodies.requestJson().flatMap(jsonPath::read).flatMap(obj -> normalizeValue(stringify(obj)));
        case RESPONSE_JSON_PATH -> bodies.responseJson().flatMap(jsonPath::read).flatMap(obj -> normalizeValue(stringify(obj)));
        case STATIC -> normalizeValue(literal);
      };
    }

    boolean requiresRequestBody() {
      return source == ValueSource.REQUEST_JSON_PATH;
    }

    boolean requiresResponseBody() {
      return source == ValueSource.RESPONSE_JSON_PATH;
    }

    boolean requiresRequestJson() {
      return source == ValueSource.REQUEST_JSON_PATH;
    }

    boolean requiresResponseJson() {
      return source == ValueSource.RESPONSE_JSON_PATH;
    }

    private Optional<String> normalizeValue(String value) {
      if (value == null) {
        return Optional.empty();
      }
      String trimmed = value.trim();
      return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }


    private String stringify(Object value) {
      return value instanceof String str ? str : String.valueOf(value);
    }
  }

  record EventTemplate(String type, Map<String, String> attributes) {
    EventTemplate {
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }

  record ExtractionResult(String userId, Map<String, String> attributes) {
    static final ExtractionResult EMPTY = new ExtractionResult(null, Map.of());
  }
}
