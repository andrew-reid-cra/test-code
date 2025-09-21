package ca.gc.cra.radar.api;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Minimal console output helper for CLI usage text.
 *
 * <p>Uses native file descriptors in order to avoid direct {@code System.out} references while
 * preserving simple stdout writes that play nicely with logging configurations.</p>
 */
public final class CliPrinter {
  private static final PrintWriter STDOUT = new PrintWriter(
      new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), true);
  private static volatile PrintWriter override;

  private CliPrinter() {
    // Utility
  }

  /**
   * Prints a single line to stdout using the shared CLI writer.
   *
   * @param message line to emit
   */
  public static void println(String message) {
    writer().println(message);
  }

  /**
   * Prints zero or more lines to stdout using the shared CLI writer.
   *
   * @param lines lines to emit
   */
  public static void printLines(String... lines) {
    if (lines == null) {
      return;
    }
    PrintWriter writer = writer();
    for (String line : lines) {
      writer.println(line);
    }
  }

  /**
   * Overrides the CLI writer for tests.
   *
   * @param writer writer to use during the test
   */
  static void setWriterForTesting(PrintWriter writer) {
    override = writer;
  }

  /**
   * Clears any test writer override.
   */
  static void clearTestWriter() {
    override = null;
  }

  /**
   * Resolves the active writer, preferring a test override.
   *
   * @return writer used for CLI output
   */
  private static PrintWriter writer() {
    return override != null ? override : STDOUT;
  }
}
