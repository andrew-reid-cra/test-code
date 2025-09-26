package ca.gc.cra.radar.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CapturePcap4jCliTest {
  @TempDir Path tempDir;

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUpLogger() {
    logger = (Logger) LoggerFactory.getLogger(CapturePcap4jCli.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDownLogger() {
    if (logger != null && appender != null) {
      logger.detachAppender(appender);
    }
    CliPrinter.clearTestWriter();
  }

  @Test
  void helpFlagPrintsUsageAndReturnsSuccess() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = CapturePcap4jCli.run(new String[] {"--help"});

    assertEquals(ExitCode.SUCCESS, code);
    assertTrue(buffer.toString().contains("capture-pcap4j"));
    assertTrue(buffer.toString().contains("pcap4j variant"));
  }

  @Test
  void malformedKeyValueReturnsInvalidArgs() {
    CliPrinter.setWriterForTesting(new PrintWriter(new StringWriter()));

    ExitCode code = CapturePcap4jCli.run(new String[] {"ifaceen0"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(hasLog(Level.ERROR, "Invalid argument"));
  }

  @Test
  void missingSourceReturnsInvalidArgs() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = CapturePcap4jCli.run(new String[] {
        "out=" + tempDir.resolve("segments"),
        "httpOut=" + tempDir.resolve("http"),
        "tnOut=" + tempDir.resolve("tn"),
        "--dry-run"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(buffer.toString().contains("usage: capture-pcap4j"));
    assertTrue(hasLog(Level.ERROR, "must supply"));
  }

  @Test
  void dryRunPrintsPlanMatchingDefaultCapture() {
    Path segments = tempDir.resolve("segments");
    Path httpOut = tempDir.resolve("http");
    Path tnOut = tempDir.resolve("tn");
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = CapturePcap4jCli.run(new String[] {
        "iface=en0",
        "out=" + segments,
        "httpOut=" + httpOut,
        "tnOut=" + tnOut,
        "--dry-run"});

    assertEquals(ExitCode.SUCCESS, code);
    String output = buffer.toString();
    assertTrue(output.contains("Capture dry-run"));
    assertTrue(output.contains("Mode             : LIVE"));
    assertTrue(output.contains("Interface        : en0"));
    assertTrue(output.contains("Segments dir     : " + segments));
    assertTrue(output.contains("Allow overwrite  : false"));
    assertTrue(Files.notExists(segments));
    assertTrue(Files.notExists(httpOut));
    assertTrue(Files.notExists(tnOut));
  }

  private boolean hasLog(Level level, String fragment) {
    List<ILoggingEvent> events = appender.list;
    return events.stream()
        .filter(event -> event.getLevel().equals(level))
        .anyMatch(event -> event.getFormattedMessage().contains(fragment));
  }
}
