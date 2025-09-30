package ca.gc.cra.radar.application.events.tn3270;

import ca.gc.cra.radar.domain.protocol.tn3270.ScreenSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple screen classification engine that evaluates TN3270 snapshots against configured rules.
 *
 * @since RADAR 1.2.0
 */
public final class ScreenRuleEngine {
  private final AtomicReference<ScreenRuleDefinitions> current =
      new AtomicReference<>(ScreenRuleDefinitions.empty());

  /**
   * Creates an engine with the supplied rule definitions.
   *
   * @param definitions initial definitions; may be {@code null}
   */
  public ScreenRuleEngine(ScreenRuleDefinitions definitions) {
    update(definitions);
  }

  /**
   * Replaces the current rule definitions.
   *
   * @param definitions new definitions; {@code null} clears all rules
   */
  public void update(ScreenRuleDefinitions definitions) {
    current.set(definitions == null ? ScreenRuleDefinitions.empty() : definitions);
  }

  /**
   * @return {@code true} when at least one rule is available
   */
  public boolean hasRules() {
    ScreenRuleDefinitions definitions = current.get();
    return definitions != null && !definitions.isEmpty();
  }

  /**
   * Evaluates the snapshot and returns the first matching screen classification.
   *
   * @param snapshot TN3270 screen snapshot; never {@code null}
   * @return optional match containing extracted label and operator id
   */
  public Optional<ScreenRuleMatch> match(ScreenSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    ScreenRuleDefinitions definitions = current.get();
    if (definitions == null || definitions.isEmpty()) {
      return Optional.empty();
    }

    List<String> lines = toLines(snapshot);
    for (ScreenRuleDefinitions.ScreenRule rule : definitions.screens()) {
      if (matches(rule, snapshot, lines)) {
        String label = extract(rule.labelExtraction(), lines, snapshot.cols());
        ScreenRuleDefinitions.UserIdExtraction userIdExtraction = rule.userIdExtraction();
        String userId = null;
        if (userIdExtraction != null) {
          userId = extract(userIdExtraction, lines, snapshot.cols());
          if (userId != null && userId.isBlank()) {
            userId = null;
          }
        }
        return Optional.of(new ScreenRuleMatch(rule.id(), rule.description(), label, userId));
      }
    }

    return Optional.empty();
  }

  private boolean matches(ScreenRuleDefinitions.ScreenRule rule, ScreenSnapshot snapshot, List<String> lines) {
    for (ScreenRuleDefinitions.MatchCondition condition : rule.matches()) {
      String actual = readRegion(condition.position(), lines, snapshot.cols());
      if (condition.trim()) {
        actual = actual.trim();
      }
      String expected = condition.trim() ? condition.equals().trim() : condition.equals();
      boolean matched = condition.ignoreCase() ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
      if (!matched) {
        return false;
      }
    }
    return true;
  }

  private String extract(ScreenRuleDefinitions.LabelExtraction extraction, List<String> lines, int cols) {
    String value = readRegion(extraction.position(), lines, cols);
    if (extraction.trim()) {
      value = value.trim();
    }
    return value;
  }

  private String extract(ScreenRuleDefinitions.UserIdExtraction extraction, List<String> lines, int cols) {
    String value = readRegion(extraction.position(), lines, cols);
    if (extraction.trim()) {
      value = value.trim();
    }
    return value;
  }

  private List<String> toLines(ScreenSnapshot snapshot) {
    String plainText = snapshot.plainText() == null ? "" : snapshot.plainText();
    plainText = plainText.replace("\r\n", "\n").replace('\r', '\n');
    String[] tokens = plainText.split("\n", -1);
    List<String> lines = new ArrayList<>(snapshot.rows());
    for (int i = 0; i < snapshot.rows(); i++) {
      String line = i < tokens.length ? tokens[i] : "";
      lines.add(padRight(line, snapshot.cols()));
    }
    return lines;
  }

  private String padRight(String line, int width) {
    if (line.length() >= width) {
      return line;
    }
    StringBuilder builder = new StringBuilder(width);
    builder.append(line);
    while (builder.length() < width) {
      builder.append(' ');
    }
    return builder.toString();
  }

  private String readRegion(ScreenRuleDefinitions.Position position, List<String> lines, int cols) {
    int rowIndex = position.row() - 1;
    if (rowIndex < 0 || rowIndex >= lines.size()) {
      return "";
    }
    String line = lines.get(rowIndex);
    int columnIndex = position.column() - 1;
    if (columnIndex < 0 || columnIndex >= cols) {
      return "";
    }
    int end = Math.min(columnIndex + position.length(), line.length());
    if (columnIndex >= end) {
      return "";
    }
    return line.substring(columnIndex, end);
  }

  /**
   * Classification result returned by the engine.
   *
   * @param screenId matched rule identifier
   * @param description optional human-readable description
   * @param label extracted screen label (may be blank)
   * @param userId extracted operator identifier, when available
   */
  public record ScreenRuleMatch(String screenId, String description, String label, String userId) {}
}