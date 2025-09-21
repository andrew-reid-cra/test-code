package ca.gc.cra.radar.api;

import ca.gc.cra.radar.api.tools.SegbinGrepCli;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RADAR CLI dispatcher that routes to subcommands.
 *
 * @since RADAR 0.1-doc
 */
public final class Main {
  private static final Logger log = LoggerFactory.getLogger(Main.class);
  private static final String SUMMARY_USAGE =
      "usage: radar <capture|live|assemble|poster|segbingrep> [options]";
  private static final String HELP_TEXT = """
      RADAR command dispatcher

      Usage:
        radar <command> [options]

      Commands:
        capture     Packet capture pipeline (capture --help for details)
        live        Live processing pipeline (live --help for details)
        assemble    Assemble captured segments into higher-level pairs
        poster      Render reconstructed traffic to files or Kafka
        segbingrep  Search segment binaries for literal byte sequences

      Global flags:
        --help      Show this message
        --verbose   Enable DEBUG logging before dispatching to subcommand
      """;

  private Main() {}

  /**
   * Entry point invoked by the JVM.
   *
   * @param args raw CLI arguments
   */
  public static void main(String[] args) {
    ExitCode exit = run(args);
    System.exit(exit.code());
  }

  /**
   * Dispatches a subcommand and returns its exit code without terminating the JVM.
   *
   * @param args dispatcher arguments (first token is the subcommand)
   * @return exit code reported by the delegated CLI
   */
  static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);
    if (input.help()) {
      CliPrinter.println(HELP_TEXT.stripTrailing());
      return ExitCode.SUCCESS;
    }
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for dispatcher");
    }

    String[] remainder = input.keyValueArgs();
    if (remainder.length == 0) {
      log.error("Missing command");
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    String command = remainder[0].toLowerCase(Locale.ROOT);
    String[] delegateArgs = Arrays.copyOfRange(remainder, 1, remainder.length);

    return switch (command) {
      case "capture" -> CaptureCli.run(delegateArgs);
      case "live" -> LiveCli.run(delegateArgs);
      case "assemble" -> AssembleCli.run(delegateArgs);
      case "poster" -> PosterCli.run(delegateArgs);
      case "segbingrep" -> SegbinGrepCli.run(delegateArgs);
      default -> {
        log.error("Unknown command: {}", command);
        CliPrinter.println(SUMMARY_USAGE);
        yield ExitCode.INVALID_ARGS;
      }
    };
  }
}
