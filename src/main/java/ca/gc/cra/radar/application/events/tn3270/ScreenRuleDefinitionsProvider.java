package ca.gc.cra.radar.application.events.tn3270;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Facade that loads TN3270 screen rule definitions from YAML sources.
 *
 * @since RADAR 1.2.0
 */
public final class ScreenRuleDefinitionsProvider {
  private final ScreenRuleDefinitionsLoader loader = new ScreenRuleDefinitionsLoader();

  /**
   * Loads screen rules from the supplied YAML files.
   *
   * @param sources ordered list of YAML documents to merge
   * @return aggregated screen rule definitions
   * @throws IOException when any source cannot be read
   */
  public ScreenRuleDefinitions load(List<Path> sources) throws IOException {
    Objects.requireNonNull(sources, "sources");
    return loader.load(sources);
  }
}