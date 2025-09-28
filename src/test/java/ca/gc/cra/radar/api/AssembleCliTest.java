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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class AssembleCliTest {
  @TempDir Path tempDir;

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;
  private Level originalLevel;
  private boolean originalAdditive;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(AssembleCli.class);
    originalLevel = logger.getLevel();
    originalAdditive = logger.isAdditive();
    logger.setAdditive(false);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (logger != null && appender != null) {
      logger.detachAppender(appender);
      appender.stop();
      logger.setAdditive(originalAdditive);
      logger.setLevel(originalLevel);
    }
    CliPrinter.clearTestWriter();
  }

  @Test
  void invalidKafkaArgsReturnUsageAndInvalidArgs() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = AssembleCli.run(new String[] {"ioMode=KAFKA", "--dry-run"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(buffer.toString().contains("usage: assemble"));
    boolean logged = appender.list.stream()
        .anyMatch(event -> event.getLevel() == Level.ERROR
            && event.getFormattedMessage().contains("kafkaBootstrap is required"));
    assertTrue(logged);
  }

  @Test
  void invalidInputDirectoryReturnsInvalidArgs() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = AssembleCli.run(new String[] {
        "in=" + tempDir.resolve("missing").toString(),
        "out=" + tempDir.resolve("out").toString()});

    assertEquals(ExitCode.INVALID_ARGS, code);
    assertTrue(buffer.toString().contains("usage: assemble"));
  }

  @Test
  void dryRunPrintsPlanAndDoesNotCreateOutputs() throws java.io.IOException {
    Path input = Files.createDirectories(tempDir.resolve("segments"));
    Path output = tempDir.resolve("out");
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = AssembleCli.run(new String[] {
        "in=" + input.toString(),
        "out=" + output.toString(),
        "--dry-run"});

    assertEquals(ExitCode.SUCCESS, code);
    String outputText = buffer.toString();
    assertTrue(outputText.contains("Assemble dry-run"));
    assertFalse(Files.exists(output), "dry-run should not create output directory");
  }
}





