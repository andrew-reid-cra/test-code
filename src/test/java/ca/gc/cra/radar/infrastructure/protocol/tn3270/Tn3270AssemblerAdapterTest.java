package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.port.Tn3270EventSink;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Event;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270EventType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class Tn3270AssemblerAdapterTest {

  @Test
  void onPairEmitsSessionEventsAndUserAction() throws Exception {
    RecordingSink sink = new RecordingSink();
    Tn3270AssemblerAdapter adapter =
        new Tn3270AssemblerAdapter(sink, new TelnetNegotiationFilter(), Function.identity(), true, 1.0d);

    adapter.onPair(Tn3270TestFixtures.messagePair());

    assertEquals(3, sink.events.size());
    assertEquals(Tn3270EventType.SESSION_START, sink.events.get(0).type());
    assertEquals(Tn3270EventType.SCREEN_RENDER, sink.events.get(1).type());

    Tn3270Event submit = sink.events.get(2);
    assertEquals(Tn3270EventType.USER_SUBMIT, submit.type());
    assertEquals("123456789", submit.inputFields().get("SIN:"));
    assertEquals("2024", submit.inputFields().get("YEAR:"));
    assertNotNull(submit.screenHash());

    adapter.close();
    assertTrue(sink.closed);
    assertEquals(4, sink.events.size());
    assertEquals(Tn3270EventType.SESSION_END, sink.events.get(3).type());
  }

  private static final class RecordingSink implements Tn3270EventSink {
    private final List<Tn3270Event> events = new ArrayList<>();
    private boolean closed;

    @Override
    public void accept(Tn3270Event event) {
      events.add(event);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  @Test
  void onStructuredFieldPairEmitsEvents() throws Exception {
    RecordingSink sink = new RecordingSink();
    Tn3270AssemblerAdapter adapter =
        new Tn3270AssemblerAdapter(sink, new TelnetNegotiationFilter(), Function.identity(), true, 1.0d);

    adapter.onPair(Tn3270TestFixtures.messagePairWithStructuredField());

    assertEquals(3, sink.events.size());
    assertEquals(Tn3270EventType.SESSION_START, sink.events.get(0).type());
    assertEquals(Tn3270EventType.SCREEN_RENDER, sink.events.get(1).type());
    assertEquals(Tn3270EventType.USER_SUBMIT, sink.events.get(2).type());
    assertTrue(sink.events.get(1).screen().plainText().contains("SIN:"));

    adapter.close();
  }
}
