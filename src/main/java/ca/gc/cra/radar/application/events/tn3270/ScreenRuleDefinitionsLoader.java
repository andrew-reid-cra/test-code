package ca.gc.cra.radar.application.events.tn3270;

import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.LabelExtraction;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.MatchCondition;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.Position;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.ScreenRule;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.UserIdExtraction;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Loads {@link ScreenRuleDefinitions} from YAML documents.
 *
 * @since RADAR 1.2.0
 */
final class ScreenRuleDefinitionsLoader {
  ScreenRuleDefinitions load(List<Path> sources) throws IOException {
    Objects.requireNonNull(sources, "sources");
    if (sources.isEmpty()) {
      return ScreenRuleDefinitions.empty();
    }

    List<ScreenRule> screens = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();
    for (Path path : sources) {
      Document document = parseDocument(path);
      for (ScreenRule rule : document.screens()) {
        if (!ids.add(rule.id())) {
          throw new IllegalArgumentException("Duplicate TN3270 screen rule id detected: " + rule.id());
        }
        screens.add(rule);
      }
    }

    return new ScreenRuleDefinitions(List.copyOf(screens));
  }

  private Document parseDocument(Path path) throws IOException {
    if (!Files.exists(path)) {
      throw new IOException("TN3270 screen rule file not found: " + path);
    }

    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      Object rootObj = new Yaml().load(reader);
      if (rootObj == null) {
        return new Document(List.of());
      }

      Map<String, Object> root = asMap(rootObj, "root");
      int version = toInt(root.get("version"), "version");
      if (version != 1) {
        throw new IllegalArgumentException("Unsupported TN3270 screen rule version " + version + " in " + path);
      }

      Object screensNode = root.get("screens");
      if (screensNode == null) {
        return new Document(List.of());
      }

      List<ScreenRule> screens = new ArrayList<>();
      if (screensNode instanceof Iterable<?> iterable) {
        for (Object item : iterable) {
          ScreenRule rule = parseScreen(asMap(item, "screen"));
          screens.add(rule);
        }
      } else {
        throw new IllegalArgumentException("screens must be a list in " + path);
      }

      return new Document(List.copyOf(screens));
    } catch (YAMLException ex) {
      throw new IllegalArgumentException("Failed to parse TN3270 screen rules at " + path, ex);
    }
  }

  private ScreenRule parseScreen(Map<String, Object> map) {
    String id = requireString(map, "id");
    String description = toOptionalString(map.get("description"));

    List<MatchCondition> matches = parseMatches(map.get("match"));
    if (matches.isEmpty()) {
      throw new IllegalArgumentException("Screen rule " + id + " must define at least one match condition");
    }

    LabelExtraction label = parseLabel(map.get("label"));
    UserIdExtraction userId = parseUserId(map.get("userId"));

    return new ScreenRule(id, description, matches, label, userId);
  }

  private List<MatchCondition> parseMatches(Object node) {
    if (node == null) {
      return List.of();
    }
    List<MatchCondition> matches = new ArrayList<>();
    if (node instanceof Iterable<?> iterable) {
      for (Object element : iterable) {
        matches.add(parseMatch(asMap(element, "match")));
      }
    } else {
      matches.add(parseMatch(asMap(node, "match")));
    }
    return List.copyOf(matches);
  }

  private MatchCondition parseMatch(Map<String, Object> map) {
    Position position = parsePosition(map, "match.position");
    String equals = requireString(map, "equals");
    boolean trim = toBoolean(map.get("trim"), true);
    boolean ignoreCase = toBoolean(map.get("ignoreCase"), false);
    return new MatchCondition(position, equals, trim, ignoreCase);
  }

  private LabelExtraction parseLabel(Object node) {
    Map<String, Object> map = asMap(node, "label");
    Position position = parsePosition(map, "label.position");
    boolean trim = toBoolean(map.get("trim"), true);
    return new LabelExtraction(position, trim);
  }

  private UserIdExtraction parseUserId(Object node) {
    if (node == null) {
      return null;
    }
    Map<String, Object> map = asMap(node, "userId");
    Position position = parsePosition(map, "userId.position");
    boolean trim = toBoolean(map.get("trim"), true);
    return new UserIdExtraction(position, trim);
  }

  private Position parsePosition(Map<String, Object> map, String context) {
    if (map.containsKey("position")) {
      Map<String, Object> posMap = asMap(map.get("position"), context);
      return parsePositionFields(posMap, context);
    }
    return parsePositionFields(map, context);
  }

  private Position parsePositionFields(Map<String, Object> map, String context) {
    int row = toInt(map.get("row"), context + ".row");
    int column = toInt(map.get("column"), context + ".column");
    int length = toInt(map.get("length"), context + ".length");
    return new Position(row, column, length);
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

  private String requireString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing required field: " + key);
    }
    String str = value.toString();
    if (str.isBlank()) {
      throw new IllegalArgumentException("Field '" + key + "' must not be blank");
    }
    return str;
  }

  private String toOptionalString(Object value) {
    if (value == null) {
      return null;
    }
    String str = value.toString();
    return str.isBlank() ? null : str;
  }

  private int toInt(Object value, String context) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String str) {
      String trimmed = str.trim();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException("Invalid integer for " + context + ": blank");
      }
      try {
        return Integer.parseInt(trimmed);
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Invalid integer for " + context + ": '" + str + "'");
      }
    }
    throw new IllegalArgumentException("Invalid integer for " + context + ": " + value);
  }

  private boolean toBoolean(Object value, boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String str) {
      return Boolean.parseBoolean(str.trim());
    }
    throw new IllegalArgumentException("Invalid boolean: " + value);
  }

  private record Document(List<ScreenRule> screens) {}
}