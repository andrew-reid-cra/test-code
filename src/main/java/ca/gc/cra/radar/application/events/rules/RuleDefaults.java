package ca.gc.cra.radar.application.events.rules;

import java.util.Map;
import java.util.Objects;

/**
 * Rule set defaults applied to every event when individual rules omit the fields.
 *
 * @since RADAR 1.1.0
 */
public record RuleDefaults(String app, String source, Map<String, String> attributes) {

  /**
   * Normalizes supplied defaults ensuring non-null metadata and defensive copies.
   *
   * @param app application identifier associated with emitted user events
   * @param source origin identifier (e.g., capture sensor or subsystem)
   * @param attributes default attribute map applied when rules omit overrides
   */
  public RuleDefaults {
    app = Objects.requireNonNull(app, "app");
    source = Objects.requireNonNull(source, "source");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /**
   * Returns an empty defaults instance.
   *
   * @return defaults with blank app/source
   */
  public static RuleDefaults empty() {
    return new RuleDefaults("", "", Map.of());
  }
}
