package ca.gc.cra.radar.application.events.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal JSONPath implementation supporting dotted paths and array indices.
 *
 * @since RADAR 1.1.0
 */
public final class JsonPathExpression {
  private final List<PathToken> tokens;

  private JsonPathExpression(List<PathToken> tokens) {
    this.tokens = tokens;
  }

  /**
   * Compiles a JSONPath expression (e.g., {@code $.foo[0].bar}).
   *
   * @param expression JSONPath string
   * @return compiled expression
   */
  public static JsonPathExpression compile(String expression) {
    Objects.requireNonNull(expression, "expression");
    String trimmed = expression.trim();
    if (trimmed.isEmpty() || trimmed.charAt(0) != '$') {
      throw new IllegalArgumentException("JSONPath must start with $");
    }
    List<PathToken> tokens = new ArrayList<>();
    int i = 1;
    while (i < trimmed.length()) {
      char c = trimmed.charAt(i);
      if (c == '.') {
        i++;
        int start = i;
        while (i < trimmed.length() && isIdentifierChar(trimmed.charAt(i))) {
          i++;
        }
        if (start == i) {
          throw new IllegalArgumentException("Empty property name in JSONPath: " + expression);
        }
        tokens.add(new FieldToken(trimmed.substring(start, i)));
      } else if (c == '[') {
        i++;
        i = skipWhitespace(trimmed, i);
        if (i >= trimmed.length()) {
          throw new IllegalArgumentException("Unterminated bracket in JSONPath: " + expression);
        }
        char next = trimmed.charAt(i);
        if (next == '\'' || next == '"') {
          char quote = next;
          i++;
          StringBuilder sb = new StringBuilder();
          boolean closed = false;
          while (i < trimmed.length()) {
            char ch = trimmed.charAt(i);
            if (ch == '\\') {
              if (i + 1 >= trimmed.length()) {
                throw new IllegalArgumentException("Invalid escape in JSONPath: " + expression);
              }
              sb.append(trimmed.charAt(i + 1));
              i += 2;
            } else if (ch == quote) {
              closed = true;
              i++;
              break;
            } else {
              sb.append(ch);
              i++;
            }
          }
          if (!closed) {
            throw new IllegalArgumentException("Unterminated string in JSONPath: " + expression);
          }
          i = skipWhitespace(trimmed, i);
          if (i >= trimmed.length() || trimmed.charAt(i) != ']') {
            throw new IllegalArgumentException("Missing closing ] in JSONPath: " + expression);
          }
          i++;
          tokens.add(new FieldToken(sb.toString()));
        } else {
          int start = i;
          boolean negative = false;
          if (next == '-') {
            negative = true;
            i++;
          }
          while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) {
            i++;
          }
          if (start == i) {
            throw new IllegalArgumentException("Invalid array index in JSONPath: " + expression);
          }
          int index = Integer.parseInt(trimmed.substring(start, i));
          if (negative) {
            throw new IllegalArgumentException("Negative indices are not supported: " + expression);
          }
          i = skipWhitespace(trimmed, i);
          if (i >= trimmed.length() || trimmed.charAt(i) != ']') {
            throw new IllegalArgumentException("Missing closing ] in JSONPath: " + expression);
          }
          i++;
          tokens.add(new IndexToken(index));
        }
      } else if (Character.isWhitespace(c)) {
        i++;
      } else {
        throw new IllegalArgumentException("Unexpected token '" + c + "' in JSONPath: " + expression);
      }
    }
    return new JsonPathExpression(List.copyOf(tokens));
  }

  /**
   * Evaluates the expression against the provided root object.
   *
   * @param root parsed JSON root (map/list/primitives)
   * @return optional value
   */
  public Optional<Object> read(Object root) {
    Object current = root;
    for (PathToken token : tokens) {
      if (current == null) {
        return Optional.empty();
      }
      current = token.resolve(current);
      if (current == PathToken.MISSING) {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(current);
  }

  private static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '$';
  }

  private static int skipWhitespace(String str, int index) {
    while (index < str.length() && Character.isWhitespace(str.charAt(index))) {
      index++;
    }
    return index;
  }

  private sealed interface PathToken permits FieldToken, IndexToken {
    Object MISSING = new Object();

    Object resolve(Object current);
  }

  private static final class FieldToken implements PathToken {
    private final String name;

    FieldToken(String name) {
      this.name = name;
    }

    @Override
    public Object resolve(Object current) {
      if (current instanceof Map<?, ?> map) {
        return map.get(name);
      }
      return MISSING;
    }
  }

  private static final class IndexToken implements PathToken {
    private final int index;

    IndexToken(int index) {
      this.index = index;
    }

    @Override
    public Object resolve(Object current) {
      if (current instanceof List<?> list) {
        if (index >= 0 && index < list.size()) {
          return list.get(index);
        }
        return MISSING;
      }
      return MISSING;
    }
  }
}
