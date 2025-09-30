package ca.gc.cra.radar.application.events.tn3270;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.events.tn3270.ScreenRuleEngine.ScreenRuleMatch;
import ca.gc.cra.radar.domain.protocol.tn3270.ScreenSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScreenRuleEngineTest {
  private static final Path PRIMARY_RULES =
      Path.of("src", "test", "resources", "tn3270", "screen-rules-primary.yaml");

  private ScreenRuleDefinitions definitions;

  @BeforeEach
  void loadDefinitions() throws IOException {
    definitions = new ScreenRuleDefinitionsLoader().load(List.of(PRIMARY_RULES));
  }

  @Test
  void matchReturnsFirstMatchingRuleWithUserId() {
    ScreenRuleEngine engine = new ScreenRuleEngine(definitions);

    ScreenSnapshot snapshot = snapshot(
        "welcome             host", // row 1 - case-insensitive match
        "Sign-on Screen Label                 ",
        "    SYSTEM   status", // row 3 - trimmed match
        "           OP123456   ",
        "    CONFIRM   operation", // row 5
        "",
        "         REJECT      "); // row 7

    Optional<ScreenRuleMatch> match = engine.match(snapshot);
    assertTrue(match.isPresent());

    ScreenRuleMatch result = match.orElseThrow();
    assertEquals("SIGNON", result.screenId());
    assertEquals("Sign-on screen requiring operator ID.", result.description());
    assertEquals("Sign-on Screen Label", result.label());
    assertEquals("OP123456", result.userId());
  }

  @Test
  void matchReturnsEmptyWhenNoRuleMatches() {
    ScreenRuleEngine engine = new ScreenRuleEngine(definitions);
    ScreenSnapshot snapshot = snapshot(
        "Unknown screen", "Different label", "   OTHER", "           XXXXXXXX   ");

    assertTrue(engine.match(snapshot).isEmpty());
  }

  @Test
  void updateClearsRulesWhenNullProvided() {
    ScreenRuleEngine engine = new ScreenRuleEngine(definitions);
    assertTrue(engine.hasRules());

    engine.update(null);

    assertFalse(engine.hasRules());
    assertTrue(engine.match(snapshot("anything")).isEmpty());
  }

  private ScreenSnapshot snapshot(String... lines) {
    String plainText = String.join("\n", lines);
    return new ScreenSnapshot(24, 80, plainText, List.of());
  }
}
