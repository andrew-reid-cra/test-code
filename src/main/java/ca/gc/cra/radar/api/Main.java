package ca.gc.cra.radar.api;

/**
 * RADAR CLI dispatcher that routes to subcommands.
 * <p>Provides the {@code radar} entry point packaged with application distributions.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class Main {
  private Main() {}

  /**
   * Dispatches to a specific CLI based on the first argument.
   *
   * @param args command array where {@code args[0]} selects the subcommand
   * @throws Exception if the delegated CLI throws
   * @since RADAR 0.1-doc
   */
  public static void main(String[] args) throws Exception {
    if (args == null || args.length == 0) {
      usage();
      return;
    }
    String command = args[0].toLowerCase();
    String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
    switch (command) {
      case "capture" -> CaptureCli.main(rest);
      case "live" -> LiveCli.main(rest);
      case "assemble" -> AssembleCli.main(rest);
      case "poster" -> PosterCli.main(rest);
      case "segbingrep" -> ca.gc.cra.radar.api.tools.SegbinGrepCli.main(rest);
      default -> usage();
    }
  }

  private static void usage() {
    System.out.println("Usage: radar <capture|live|assemble|poster|segbingrep> [options]");
  }
}
