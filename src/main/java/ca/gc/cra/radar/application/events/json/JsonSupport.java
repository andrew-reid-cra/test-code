package ca.gc.cra.radar.application.events.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal JSON helper that parses payloads into {@link Map}/{@link List} structures for rule evaluation.
 *
 * @since RADAR 1.1.0
 */
public final class JsonSupport {
  private final JsonFactory factory = new JsonFactory();

  /**
   * Parses the supplied JSON string into a mutable object graph of maps, lists, and primitives.
   *
   * @param json JSON document; never {@code null}
   * @return parsed object graph
   * @throws IllegalArgumentException when parsing fails
   */
  public Object parse(String json) {
    Objects.requireNonNull(json, "json");
    try (JsonParser parser = factory.createParser(json)) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        return Map.of();
      }
      Object value = readValue(parser, token);
      JsonToken trailing = parser.nextToken();
      if (trailing != null && trailing != JsonToken.NOT_AVAILABLE) {
        throw new IllegalArgumentException("JSON document contains trailing content");
      }
      return value;
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid JSON payload", ex);
    }
  }

  /**
   * Evaluates a compiled JSONPath expression against the supplied root object.
   *
   * @param root parsed JSON object graph (maps/lists/primitives)
   * @param path compiled JSON path expression
   * @return optional matching value
   */
  public Optional<Object> read(Object root, JsonPathExpression path) {
    if (root == null || path == null) {
      return Optional.empty();
    }
    return path.read(root);
  }

  private Object readValue(JsonParser parser, JsonToken token) throws IOException {
    return switch (token) {
      case START_OBJECT -> readObject(parser);
      case START_ARRAY -> readArray(parser);
      case VALUE_STRING -> parser.getText();
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getNumberValue();
      case VALUE_TRUE -> Boolean.TRUE;
      case VALUE_FALSE -> Boolean.FALSE;
      case VALUE_NULL -> null;
      default -> throw new IllegalArgumentException("Unsupported JSON token: " + token);
    };
  }

  private Map<String, Object> readObject(JsonParser parser) throws IOException {
    Map<String, Object> map = new LinkedHashMap<>();
    while (true) {
      JsonToken token = parser.nextToken();
      if (token == JsonToken.END_OBJECT) {
        break;
      }
      if (token != JsonToken.FIELD_NAME) {
        throw new IllegalArgumentException("Expected field name but found " + token);
      }
      String fieldName = parser.getCurrentName();
      JsonToken valueToken = parser.nextToken();
      map.put(fieldName, readValue(parser, valueToken));
    }
    return map;
  }

  private List<Object> readArray(JsonParser parser) throws IOException {
    List<Object> list = new ArrayList<>();
    while (true) {
      JsonToken token = parser.nextToken();
      if (token == JsonToken.END_ARRAY) {
        break;
      }
      list.add(readValue(parser, token));
    }
    return list;
  }
}
