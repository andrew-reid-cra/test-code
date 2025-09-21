package ca.gc.cra.radar.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsed representation of CLI arguments split into flags and key/value pairs.
 */
public final class CliInput {
  private static final Set<String> HELP_FLAGS = Set.of("--help", "-h", "help");
  private static final Set<String> VERBOSE_FLAGS = Set.of("--verbose", "-v", "--debug");

  private final String[] keyValueArgs;
  private final boolean help;
  private final boolean verbose;

  private CliInput(String[] keyValueArgs, boolean help, boolean verbose) {
    this.keyValueArgs = keyValueArgs;
    this.help = help;
    this.verbose = verbose;
  }

  /**
   * Parses raw arguments into flag and key/value partitions.
   *
   * @param args raw CLI arguments (may be {@code null})
   * @return parsed representation of the arguments
   */
  public static CliInput parse(String[] args) {
    if (args == null || args.length == 0) {
      return new CliInput(new String[0], false, false);
    }

    List<String> kv = new ArrayList<>();
    boolean help = false;
    boolean verbose = false;
    for (String raw : args) {
      String arg = raw == null ? "" : raw.trim();
      String lower = arg.toLowerCase(Locale.ROOT);
      if (HELP_FLAGS.contains(lower)) {
        help = true;
        continue;
      }
      if (VERBOSE_FLAGS.contains(lower)) {
        verbose = true;
        continue;
      }
      kv.add(raw);
    }
    return new CliInput(kv.toArray(String[]::new), help, verbose);
  }

  /**
   * Returns a defensive copy of the key/value style arguments.
   *
   * @return copy of arguments intended for key=value parsing
   */
  public String[] keyValueArgs() {
    return Arrays.copyOf(keyValueArgs, keyValueArgs.length);
  }

  /**
   * Indicates whether a help flag was supplied.
   *
   * @return {@code true} if help output was requested
   */
  public boolean help() {
    return help;
  }

  /**
   * Indicates whether verbose logging was requested.
   *
   * @return {@code true} when --verbose (or equivalent) was present
   */
  public boolean verbose() {
    return verbose;
  }
}
