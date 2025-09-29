package ca.gc.cra.radar.application.events.rules;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Facade that loads and compiles user event rules from YAML sources.
 *
 * @since RADAR 1.1.0
 */
public final class UserEventRuleSetProvider {
  private final RuleSetLoader loader = new RuleSetLoader();
  private final RuleSetCompiler compiler = new RuleSetCompiler();

  /**
   * Loads and compiles rules from the supplied YAML files.
   *
   * @param sources ordered list of rule files
   * @return compiled rule set
   * @throws IOException when any source cannot be read
   */
  public CompiledRuleSet load(List<Path> sources) throws IOException {
    Objects.requireNonNull(sources, "sources");
    RuleSetLoader.LoadedRuleSet loaded = loader.load(sources);
    return compiler.compile(loaded.defaults(), loaded.rules());
  }
}
