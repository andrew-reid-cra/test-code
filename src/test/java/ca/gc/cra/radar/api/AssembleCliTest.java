package ca.gc.cra.radar.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AssembleCliTest {
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(AssembleCli.class);
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
  void invalidKafkaArgsReturnUsageAndInvalidArgs() {
    StringWriter buffer = new StringWriter();
    CliPrinter.setWriterForTesting(new PrintWriter(buffer));

    ExitCode code = AssembleCli.run(new String[] {"ioMode=KAFKA"});

    assertEquals(ExitCode.INVALID_ARGS, code);
    String usage = buffer.toString();
    assertTrue(usage.contains("usage: assemble"), "usage text should be printed");
    boolean logged = appender.list.stream()
        .anyMatch(event -> event.getLevel() == Level.ERROR
            && event.getFormattedMessage().contains("kafkaBootstrap is required"));
    assertTrue(logged, "should log configuration guidance at error level");
  }
}
