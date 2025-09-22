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

class CaptureCliTest {
  @TempDir Path tempDir;

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(CaptureCli.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (logger != null && appender != null) {
      logger.detachAppender(appender);
    }
    CliPrinter.clearTestWriter();
  }

  @Test
  void customBpfWithoutEnableFlagIsRejected() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));
    Path httpOut = tempDir.resolve("http-out");
    Path tnOut = tempDir.resolve("tn-out");

    ExitCode code = CaptureCli.run(new String[] {
        "iface=en0",
        "bpf=tcp port 80",
        "out=" + tempDir.resolve("segments"),
        "httpOut=" + httpOut,
        "tnOut=" + tnOut,
        "--dry-run"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(buffer.toString().contains("usage: capture"));
    assertTrue(hasLogContaining("bpf requires --enable-bpf"));
  }

  @Test
  void customBpfWithEnableFlagAllowedOnDryRun() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));
    Path httpOut = tempDir.resolve("http-out");
    Path tnOut = tempDir.resolve("tn-out");

    ExitCode code = CaptureCli.run(new String[] {
        "iface=en0",
        "bpf=tcp port 80",
        "out=" + tempDir.resolve("segments"),
        "httpOut=" + httpOut,
        "tnOut=" + tnOut,
        "--enable-bpf",
        "--dry-run"});

    assertEquals(ExitCode.SUCCESS, code);
    assertTrue(buffer.toString().contains("BPF filter       : custom"));
    assertTrue(Files.notExists(httpOut));
    assertTrue(Files.notExists(tnOut));
  }

  @Test
  void invalidKafkaBootstrapIsRejected() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = CaptureCli.run(new String[] {
        "iface=en0",
        "out=kafka:radar.capture",
        "kafkaBootstrap=localhost",
        "--dry-run"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(buffer.toString().contains("usage: capture"));
    assertTrue(hasLogContaining("host:port"));
  }

  @Test
  void snapOutOfRangeIsRejected() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = CaptureCli.run(new String[] {
        "iface=en0",
        "snap=1",
        "out=" + tempDir.resolve("segments"),
        "httpOut=" + tempDir.resolve("http"),
        "tnOut=" + tempDir.resolve("tn"),
        "--dry-run"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(hasLogContaining("snap must be"));
  }

  @Test
  void dryRunDoesNotCreateOutputDirectories() {
    Path segments = tempDir.resolve("segments");
    Path httpOut = tempDir.resolve("http");
    Path tnOut = tempDir.resolve("tn");
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = CaptureCli.run(new String[] {
        "iface=en0",
        "out=" + segments,
        "httpOut=" + httpOut,
        "tnOut=" + tnOut,
        "--dry-run"});

    assertEquals(ExitCode.SUCCESS, code);
    assertTrue(buffer.toString().contains("Capture dry-run"));
    assertTrue(Files.notExists(segments));
    assertTrue(Files.notExists(httpOut));
    assertTrue(Files.notExists(tnOut));
  }

  private boolean hasLogContaining(String fragment) {
    List<ILoggingEvent> events = appender.list;
    return events.stream().anyMatch(event -> event.getFormattedMessage().contains(fragment));
  }
}


