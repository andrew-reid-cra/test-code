package ca.gc.cra.radar.api;

public final class Main {
  private Main() {}

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




