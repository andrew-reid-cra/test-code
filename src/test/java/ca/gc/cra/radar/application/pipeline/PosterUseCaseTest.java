package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PosterUseCaseTest {
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(PosterUseCase.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (logger != null && appender != null) {
      logger.detachAppender(appender);
    }
  }

  @Test
  void failingPipelineLogsErrorAndClosesOutput() throws Exception {
    PosterPipeline failing = new FailingPipeline();
    PosterUseCase useCase = new PosterUseCase(Map.of(ProtocolId.HTTP, failing));
    PosterConfig config = PosterConfig.fromMap(Map.of(
        "httpIn", "./http-in",
        "httpOut", "./http-out"));

    RuntimeException ex = assertThrows(RuntimeException.class, () -> useCase.run(config));
    assertEquals("boom", ex.getMessage());

    boolean errorLogged = appender.list.stream()
        .anyMatch(event -> event.getLevel() == Level.ERROR
            && event.getFormattedMessage().contains("Poster HTTP pipeline failed"));
    assertTrue(errorLogged, "pipeline failure should be logged once");

    boolean closeLogged = appender.list.stream()
        .anyMatch(event -> event.getLevel() == Level.INFO
            && event.getFormattedMessage().contains("Poster HTTP output closed"));
    assertTrue(closeLogged, "output closure should be logged");
  }

  private static final class FailingPipeline implements PosterPipeline {
    @Override
    public ProtocolId protocol() {
      return ProtocolId.HTTP;
    }

    @Override
    public void process(PosterConfig.ProtocolConfig config, PosterConfig.DecodeMode decodeMode, PosterOutputPort outputPort) {
      throw new RuntimeException("boom");
    }
  }
}
