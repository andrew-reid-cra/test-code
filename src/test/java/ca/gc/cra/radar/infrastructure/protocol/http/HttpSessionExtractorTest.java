package ca.gc.cra.radar.infrastructure.protocol.http;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpSessionExtractorTest {
  @Test
  void extractsFromCookie() {
    HttpSessionExtractor extractor = new HttpSessionExtractor(List.of("JSESSIONID"), List.of());
    String val = extractor.fromRequestHeaders(Map.of("cookie", "foo=bar; JSESSIONID=abc123"));
    assertEquals("SID:abc123", val);
  }

  @Test
  void extractsFromHeaderPattern() {
    HttpSessionExtractor extractor = new HttpSessionExtractor(List.of(), List.of("Authorization\\s*:\\s*Bearer\\s+(\\S+)"));
    String val = extractor.fromRequestHeaders(Map.of("Authorization", "Bearer token123"));
    assertEquals("HDR:token123", val);
  }
}


