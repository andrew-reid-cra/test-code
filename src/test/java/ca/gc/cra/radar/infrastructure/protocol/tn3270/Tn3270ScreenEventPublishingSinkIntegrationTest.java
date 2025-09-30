package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleEngine;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.TerminalEventEmitter;
import ca.gc.cra.radar.application.port.Tn3270EventSink;
import ca.gc.cra.radar.domain.events.TerminalEvent;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class Tn3270ScreenEventPublishingSinkIntegrationTest {

  @Test
  void userSubmitEmitsTerminalEventWithRuleMetadata() throws Exception {
    ScreenRuleDefinitions definitions = new ScreenRuleDefinitions(List.of(buildSinScreenRule()));
    ScreenRuleEngine engine = new ScreenRuleEngine(definitions);
    RecordingMetrics metrics = new RecordingMetrics();
    RecordingTerminalEmitter emitter = new RecordingTerminalEmitter();
    RecordingTnSink delegate = new RecordingTnSink();

    Tn3270ScreenEventPublishingSink sink = new Tn3270ScreenEventPublishingSink(
        delegate,
        engine,
        emitter,
        metrics,
        "tn3270TerminalsTest.publisher",
        "test-source",
        "test-app");

    Tn3270AssemblerAdapter adapter =
        new Tn3270AssemblerAdapter(sink, new TelnetNegotiationFilter(), Function.identity(), true, 1.0d);

    adapter.onPair(Tn3270TestFixtures.messagePair());
    adapter.close();

    assertEquals(4, delegate.events.size(), "Expected assembler to emit session lifecycle and submit events");
    assertEquals(1, emitter.events.size(), "Expected a single terminal event emission");

    ScreenRuleEngine.ScreenRuleMatch match = engine.match(delegate.events.get(1).screen()).orElseThrow();
    assertEquals("SIN_SCREEN", match.screenId());
    assertEquals("SIN:", match.label());

    Tn3270Event submitEvent = delegate.events.get(2);
    assertEquals("123456789", submitEvent.inputFields().get("SIN:"));
    assertEquals("2024", submitEvent.inputFields().get("YEAR:"));

    TerminalEvent event = emitter.events.get(0);
    assertEquals("terminal.input", event.eventType());
    assertEquals("test-source", event.source());
    assertEquals("test-app", event.app());
    assertEquals("123456789", event.operatorId());
    assertTrue(event.terminalId().contains("10.0.0.1"));
    assertNotNull(event.timestamp());

    Map<String, String> attributes = event.attributes();
    assertEquals("SIN_SCREEN", attributes.get("screen.id"));
    assertEquals("SIN:", attributes.get("screen.label"));
    assertEquals("123456789", attributes.get("input.sin"));
    assertEquals("2024", attributes.get("input.year"));

    assertEquals(1L, metrics.counter("tn3270TerminalsTest.publisher.emitted"));
    assertEquals(1L, metrics.counter("tn3270TerminalsTest.publisher.submit.received"));
    assertEquals(1L, metrics.counter("tn3270TerminalsTest.publisher.submit.match"));
    assertEquals(event.attributes().size(), metrics.observation("tn3270TerminalsTest.publisher.attributes.size"));
  }

  private ScreenRuleDefinitions.ScreenRule buildSinScreenRule() {
    ScreenRuleDefinitions.Position matchPosition = new ScreenRuleDefinitions.Position(1, 2, 4);
    ScreenRuleDefinitions.MatchCondition condition =
        new ScreenRuleDefinitions.MatchCondition(matchPosition, "SIN:", true, false);
    ScreenRuleDefinitions.LabelExtraction label =
        new ScreenRuleDefinitions.LabelExtraction(matchPosition, true);
    ScreenRuleDefinitions.UserIdExtraction userId =
        new ScreenRuleDefinitions.UserIdExtraction(new ScreenRuleDefinitions.Position(1, 7, 9), true);
    return new ScreenRuleDefinitions.ScreenRule(
        "SIN_SCREEN",
        "Social insurance capture screen",
        List.of(condition),
        label,
        userId);
  }

  private static final class RecordingTerminalEmitter implements TerminalEventEmitter {
    private final List<TerminalEvent> events = new ArrayList<>();

    @Override
    public void emit(TerminalEvent event) {
      events.add(event);
    }
  }

  private static final class RecordingTnSink implements Tn3270EventSink {
    private final List<Tn3270Event> events = new ArrayList<>();

    @Override
    public void accept(Tn3270Event event) {
      events.add(event);
    }
  }

  private static final class RecordingMetrics implements MetricsPort {
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final Map<String, Integer> observations = new ConcurrentHashMap<>();

    @Override
    public void increment(String key) {
      counters.merge(key, 1L, Long::sum);
    }

    @Override
    public void observe(String key, long value) {
      observations.put(key, (int) value);
    }

    long counter(String key) {
      return counters.getOrDefault(key, 0L);
    }

    int observation(String key) {
      return observations.getOrDefault(key, -1);
    }
  }
}


















