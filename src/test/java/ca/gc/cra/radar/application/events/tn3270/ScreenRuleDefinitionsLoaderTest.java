package ca.gc.cra.radar.application.events.tn3270;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.LabelExtraction;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.MatchCondition;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.ScreenRule;
import ca.gc.cra.radar.application.events.tn3270.ScreenRuleDefinitions.UserIdExtraction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScreenRuleDefinitionsLoaderTest {
  private static final Path PRIMARY_RULES =
      Path.of("src", "test", "resources", "tn3270", "screen-rules-primary.yaml");
  private static final Path ADDITIONAL_RULES =
      Path.of("src", "test", "resources", "tn3270", "screen-rules-additional.yaml");

  private final ScreenRuleDefinitionsLoader loader = new ScreenRuleDefinitionsLoader();

  @Test
  void loadReturnsEmptyWhenNoSourcesSupplied() throws IOException {
    ScreenRuleDefinitions definitions = loader.load(List.of());
    assertNotNull(definitions);
    assertTrue(definitions.isEmpty());
  }

  @Test
  void loadParsesSingleDocumentWithExpectations() throws IOException {
    ScreenRuleDefinitions definitions = loader.load(List.of(PRIMARY_RULES));

    assertFalse(definitions.isEmpty());
    assertEquals(2, definitions.screens().size());

    ScreenRule signOn = definitions.screens().get(0);
    assertEquals("SIGNON", signOn.id());
    assertEquals("Sign-on screen requiring operator ID.", signOn.description());

    List<MatchCondition> matches = signOn.matches();
    assertEquals(2, matches.size());

    MatchCondition firstMatch = matches.get(0);
    assertEquals(1, firstMatch.position().row());
    assertEquals(1, firstMatch.position().column());
    assertEquals(20, firstMatch.position().length());
    assertEquals("Welcome", firstMatch.equals());
    assertTrue(firstMatch.trim());
    assertTrue(firstMatch.ignoreCase());

    LabelExtraction label = signOn.labelExtraction();
    assertEquals(2, label.position().row());
    assertEquals(1, label.position().column());
    assertEquals(40, label.position().length());
    assertTrue(label.trim());

    UserIdExtraction userId = signOn.userIdExtraction();
    assertNotNull(userId);
    assertEquals(4, userId.position().row());
    assertEquals(12, userId.position().column());
    assertEquals(8, userId.position().length());
    assertTrue(userId.trim());

    ScreenRule confirm = definitions.screens().get(1);
    assertEquals("CONFIRM", confirm.id());
    assertEquals(2, confirm.matches().size());
    assertNull(confirm.userIdExtraction());
  }

  @Test
  void loadMergesMultipleDocumentsPreservingOrder() throws IOException {
    ScreenRuleDefinitions definitions = loader.load(List.of(PRIMARY_RULES, ADDITIONAL_RULES));

    assertEquals(3, definitions.screens().size());
    assertEquals("SIGNON", definitions.screens().get(0).id());
    assertEquals("CONFIRM", definitions.screens().get(1).id());
    assertEquals("STATUS", definitions.screens().get(2).id());
  }

  @Test
  void loadRejectsDuplicateIds(@TempDir Path tempDir) throws IOException {
    Path duplicate = tempDir.resolve("duplicate.yaml");
    Files.writeString(
        duplicate,
        "version: 1\nscreens:\n  - id: SIGNON\n    match:\n      position:\n        row: 1\n        column: 1\n        length: 1\n      equals: X\n    label:\n      position:\n        row: 1\n        column: 1\n        length: 1\n",
        StandardCharsets.UTF_8);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> loader.load(List.of(PRIMARY_RULES, duplicate)));

    assertTrue(ex.getMessage().contains("Duplicate TN3270 screen rule id"));
  }
}
