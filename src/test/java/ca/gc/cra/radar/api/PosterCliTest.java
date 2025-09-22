package ca.gc.cra.radar.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class PosterCliTest {
  @TempDir Path tempDir;

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(PosterCli.class);
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
  void missingInputsReturnsInvalidArgs() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = PosterCli.run(new String[] {"httpOut=" + tempDir.resolve("reports")});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(buffer.toString().contains("usage: poster"));
    assertTrue(appender.list.stream()
        .anyMatch(event -> event.getLevel() == Level.ERROR
            && event.getFormattedMessage().contains("Invalid poster configuration")));
  }

  @Test
  void invalidKafkaBootstrapIsRejected() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = PosterCli.run(new String[] {
        "httpIn=kafka:radar.http.pairs",
        "kafkaBootstrap=localhost",
        "posterOutMode=KAFKA",
        "kafkaHttpReportsTopic=radar.http.reports"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(buffer.toString().contains("usage: poster"));
    assertTrue(appender.list.stream()
        .anyMatch(event -> event.getFormattedMessage().contains("host:port")));
  }

  @Test
  void dryRunPrintsPlanAndDoesNotWriteOutputs() throws Exception {
    Path httpIn = Files.createDirectories(tempDir.resolve("http-in"));
    Path httpOut = tempDir.resolve("http-out");
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = PosterCli.run(new String[] {
        "httpIn=" + httpIn.toString(),
        "httpOut=" + httpOut.toString(),
        "--dry-run"});

    assertEquals(ExitCode.SUCCESS, code);
    assertTrue(buffer.toString().contains("Poster dry-run"));
    assertTrue(Files.notExists(httpOut), "dry-run should not create output directories");
  }
}

