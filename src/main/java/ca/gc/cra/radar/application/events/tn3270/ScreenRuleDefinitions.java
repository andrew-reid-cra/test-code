package ca.gc.cra.radar.application.events.tn3270;

import java.util.List;
import java.util.Objects;

/**
 * Immutable collection of TN3270 screen rule definitions backed by YAML configuration.
 * <p><strong>Role:</strong> Provides screen matching metadata consumed by {@link ScreenRuleEngine} to classify
 * screens and extract contextual values.</p>
 *
 * @since RADAR 1.2.0
 */
public final class ScreenRuleDefinitions {
  private final List<ScreenRule> screens;

  /**
   * Creates a definitions container.
   *
   * @param screens ordered list of screen rules; never {@code null}
   */
  public ScreenRuleDefinitions(List<ScreenRule> screens) {
    this.screens = List.copyOf(Objects.requireNonNull(screens, "screens"));
  }

  /**
   * @return immutable screen rules
   */
  public List<ScreenRule> screens() {
    return screens;
  }

  /**
   * @return {@code true} when no screen rules are available
   */
  public boolean isEmpty() {
    return screens.isEmpty();
  }

  /**
   * @return empty definitions instance
   */
  public static ScreenRuleDefinitions empty() {
    return new ScreenRuleDefinitions(List.of());
  }

  /**
   * Definition describing how to match and extract data from a screen.
   *
   * @param id unique identifier for the rule; never {@code null}
   * @param description optional human-readable description; may be {@code null}
   * @param matches ordered match predicates that must all succeed; never {@code null}
   * @param labelExtraction configuration describing how to capture the screen label; never {@code null}
   * @param userIdExtraction optional configuration describing how to capture the operator id; may be {@code null}
   */
  public record ScreenRule(
      String id,
      String description,
      List<MatchCondition> matches,
      LabelExtraction labelExtraction,
      UserIdExtraction userIdExtraction) {

    public ScreenRule {
      id = Objects.requireNonNull(id, "id");
      matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
      labelExtraction = Objects.requireNonNull(labelExtraction, "labelExtraction");
    }
  }

  /**
   * Position-based predicate used to confirm that a screen matches a rule.
   *
   * @param position location metadata
   * @param equals expected value extracted from the position; never {@code null}
   * @param trim whether leading/trailing whitespace should be trimmed before comparison
   * @param ignoreCase whether comparison should be case-insensitive
   */
  public record MatchCondition(Position position, String equals, boolean trim, boolean ignoreCase) {
    public MatchCondition {
      position = Objects.requireNonNull(position, "position");
      equals = Objects.requireNonNull(equals, "equals");
    }
  }

  /**
   * Configures how to extract a descriptive label from the screen.
   *
   * @param position region to capture
   * @param trim whether to trim the captured text
   */
  public record LabelExtraction(Position position, boolean trim) {
    public LabelExtraction {
      position = Objects.requireNonNull(position, "position");
    }
  }

  /**
   * Configures how to extract the operator/user identifier from the screen.
   *
   * @param position region to capture
   * @param trim whether to trim the captured text
   */
  public record UserIdExtraction(Position position, boolean trim) {
    public UserIdExtraction {
      position = Objects.requireNonNull(position, "position");
    }
  }

  /**
   * Identifies a rectangular slice of the terminal buffer.
   *
   * @param row one-based row index; must be {@code >= 1}
   * @param column one-based column index; must be {@code >= 1}
   * @param length number of columns to capture; must be {@code >= 1}
   */
  public record Position(int row, int column, int length) {
    public Position {
      if (row < 1) {
        throw new IllegalArgumentException("row must be >= 1 (was " + row + ')');
      }
      if (column < 1) {
        throw new IllegalArgumentException("column must be >= 1 (was " + column + ')');
      }
      if (length < 1) {
        throw new IllegalArgumentException("length must be >= 1 (was " + length + ')');
      }
    }
  }
}