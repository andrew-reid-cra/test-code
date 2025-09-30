package ca.gc.cra.radar.infrastructure.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.events.TerminalEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingTerminalEventEmitterTest {
  @Test
  void emitLogsEventAndUpdatesMetrics() {
    RecordingMetrics metrics = new RecordingMetrics();
    LoggingTerminalEventEmitter emitter = new LoggingTerminalEventEmitter(metrics, "testTerminals");

    Logger logger = (Logger) LoggerFactory.getLogger(LoggingTerminalEventEmitter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    boolean originalAdditive = logger.isAdditive();
    logger.setAdditive(false);
    appender.start();
    logger.addAppender(appender);

    try {
      TerminalEvent event = new TerminalEvent(
          Instant.parse("2024-01-01T00:00:00Z"),
          "lu1",
          "session-1",
          "terminal.input",
          "operator42",
          "tn3270-gateway",
          "radar-app",
          Map.of("screen", "signon"),
          "trace-123");

      emitter.emit(event);
    } finally {
      logger.detachAppender(appender);
      logger.setAdditive(originalAdditive);
      appender.stop();
    }

    assertEquals(1L, metrics.counter("testTerminals.emitted"));
    assertEquals(1L, metrics.observation("testTerminals.attributes.size"));

    List<ILoggingEvent> events = appender.list;
    assertEquals(1, events.size());
    String message = events.get(0).getFormattedMessage();
    assertTrue(message.startsWith("terminal.event timestamp="));
    assertTrue(message.contains("type=terminal.input"));
    assertTrue(message.contains("attributes=[screen=signon]"));
  }

  @Test
  void emitRejectsNullEvent() {
    RecordingMetrics metrics = new RecordingMetrics();
    LoggingTerminalEventEmitter emitter = new LoggingTerminalEventEmitter(metrics);

    assertThrows(NullPointerException.class, () -> emitter.emit(null));
    assertEquals(0L, metrics.counter("terminalEvents.emitted"));
  }

  private static final class RecordingMetrics implements MetricsPort {
    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, Long> observations = new HashMap<>();

    @Override
    public void increment(String key) {
      counters.merge(key, 1L, Long::sum);
    }

    @Override
    public void observe(String key, long value) {
      observations.put(key, value);
    }

    long counter(String key) {
      return counters.getOrDefault(key, 0L);
    }

    long observation(String key) {
      return observations.getOrDefault(key, 0L);
    }
  }
}