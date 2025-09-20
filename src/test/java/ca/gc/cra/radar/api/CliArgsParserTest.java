package ca.gc.cra.radar.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CliArgsParserTest {
  @Test
  void parsesKeyValuePairs() {
    Map<String, String> map = CliArgsParser.toMap(new String[] {"foo=bar", "key=value"});
    assertEquals("bar", map.get("foo"));
    assertEquals("value", map.get("key"));
  }

  @Test
  void ignoresInvalidArgs() {
    Map<String, String> map = CliArgsParser.toMap(new String[] {"invalid", "key=value"});
    assertFalse(map.containsKey("invalid"));
    assertEquals("value", map.get("key"));
  }
}


