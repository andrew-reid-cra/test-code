package ca.gc.cra.radar.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StringsTest {

  @Test
  void requireNonBlankStripsWhitespace() {
    assertEquals("value", Strings.requireNonBlank("test", "  value  "));
  }

  @Test
  void requireNonBlankRejectsControlCharacters() {
    assertThrows(IllegalArgumentException.class, () -> Strings.requireNonBlank("test", "bad\u0001"));
  }

  @Test
  void sanitizeTopicAllowsSafeCharacters() {
    assertEquals("radar.topic-1", Strings.sanitizeTopic("radar.topic-1"));
  }

  @Test
  void sanitizeTopicRejectsInvalidCharacters() {
    assertThrows(IllegalArgumentException.class, () -> Strings.sanitizeTopic("radar topic"));
  }

  @Test
  void requirePrintableAsciiRejectsNonAscii() {
    assertThrows(IllegalArgumentException.class, () -> Strings.requirePrintableAscii("bpf", "v\u2603l", 16));
  }

  @Test
  void requirePrintableAsciiRejectsExcessLength() {
    assertThrows(IllegalArgumentException.class, () -> Strings.requirePrintableAscii("bpf", "abc", 2));
  }
}

