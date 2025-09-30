package ca.gc.cra.radar.domain.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalEventTest {
  @Test
  void rejectsNullMandatoryFields() {
    Map<String, String> attributes = Map.of();
    Instant now = Instant.parse("2024-01-01T00:00:00Z");

    assertThrows(NullPointerException.class, () ->
        new TerminalEvent(null, "lu1", "session", "terminal.input", null, "gateway", "app", attributes, null));
    assertThrows(NullPointerException.class, () ->
        new TerminalEvent(now, null, "session", "terminal.input", null, "gateway", "app", attributes, null));
    assertThrows(NullPointerException.class, () ->
        new TerminalEvent(now, "lu1", null, "terminal.input", null, "gateway", "app", attributes, null));
    assertThrows(NullPointerException.class, () ->
        new TerminalEvent(now, "lu1", "session", null, null, "gateway", "app", attributes, null));
    assertThrows(NullPointerException.class, () ->
        new TerminalEvent(now, "lu1", "session", "terminal.input", null, null, "app", attributes, null));
    assertThrows(NullPointerException.class, () ->
        new TerminalEvent(now, "lu1", "session", "terminal.input", null, "gateway", null, attributes, null));
  }

  @Test
  void attributesAreDefensivelyCopiedAndImmutable() {
    Map<String, String> mutable = new HashMap<>();
    mutable.put("screen", "signon");
    TerminalEvent event = new TerminalEvent(
        Instant.parse("2024-01-01T00:00:00Z"),
        "lu1",
        "session",
        "terminal.input",
        "operator42",
        "gateway",
        "app",
        mutable,
        "trace-1");

    mutable.put("screen", "menu");

    assertEquals("signon", event.attributes().get("screen"));
    assertThrows(UnsupportedOperationException.class, () -> event.attributes().put("k", "v"));
  }

  @Test
  void nullAttributesYieldEmptyMap() {
    TerminalEvent event = new TerminalEvent(
        Instant.parse("2024-01-01T00:00:00Z"),
        "lu1",
        "session",
        "terminal.input",
        null,
        "gateway",
        "app",
        null,
        null);

    assertTrue(event.attributes().isEmpty());
  }
}