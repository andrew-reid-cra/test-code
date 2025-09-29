package ca.gc.cra.radar.application.events;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.events.http.HttpBodyView;
import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.http.HttpRequestContext;
import ca.gc.cra.radar.application.events.http.HttpResponseContext;
import ca.gc.cra.radar.domain.events.AuthState;
import ca.gc.cra.radar.domain.events.SessionInfo;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionResolverTest {

  private final SessionResolver resolver = new SessionResolver();

  @Test
  void resolvesFromCookiesThenHeaders() {
    HttpRequestContext request = requestContext(
        Map.of("jsessionid", List.of("header123")),
        Map.of("jsessionid", "abc"),
        Map.of(),
        1_000L);
    HttpResponseContext response = responseContext(Map.of(), 2_000L);
    SessionInfo session = resolver.resolve(new HttpExchangeContext(request, response), null);
    assertEquals("abc", session.sessionId());
    assertEquals(AuthState.AUTHENTICATED, session.authState());
  }

  @Test
  void resolvesFromResponseSetCookie() {
    HttpRequestContext request = requestContext(Map.of(), Map.of(), Map.of(), 1_000L);
    HttpResponseContext response = responseContext(Map.of(
        "set-cookie", List.of("SMSessionID=resp123; Path=/")), 2_000L);
    SessionInfo session = resolver.resolve(new HttpExchangeContext(request, response), null);
    assertEquals("resp123", session.sessionId());
    assertEquals(AuthState.AUTHENTICATED, session.authState());
  }

  @Test
  void derivesFallbackWhenMissing() {
    HttpRequestContext request = requestContext(Map.of(), Map.of(), Map.of(), 1_000_000L);
    HttpResponseContext response = responseContext(Map.of(), 2_000_000L);
    SessionInfo session = resolver.resolve(new HttpExchangeContext(request, response), null);
    assertTrue(session.sessionId().startsWith("DERIVED-"));
    assertEquals(AuthState.UNAUTHENTICATED, session.authState());
  }

  @Test
  void userIdMarksAuthenticated() {
    HttpRequestContext request = requestContext(Map.of(), Map.of(), Map.of(), 1_000L);
    HttpResponseContext response = responseContext(Map.of(), 2_000L);
    SessionInfo session = resolver.resolve(new HttpExchangeContext(request, response), "user-77");
    assertEquals("user-77", session.userId());
    assertEquals(AuthState.AUTHENTICATED, session.authState());
  }

  private HttpRequestContext requestContext(
      Map<String, List<String>> headers,
      Map<String, String> cookies,
      Map<String, String> query,
      long timestamp) {
    Map<String, List<String>> normalizedHeaders = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      normalizedHeaders.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return new HttpRequestContext(
        "GET",
        "/path",
        "/path",
        "HTTP/1.1",
        Map.copyOf(normalizedHeaders),
        Map.copyOf(new java.util.LinkedHashMap<>(cookies)),
        query,
        new HttpBodyView(new byte[0], StandardCharsets.UTF_8),
        "203.0.113.1",
        54321,
        "198.51.100.1",
        443,
        timestamp);
  }

  private HttpResponseContext responseContext(Map<String, List<String>> headers, long timestamp) {
    Map<String, List<String>> normalizedHeaders = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      normalizedHeaders.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return new HttpResponseContext(
        200,
        "OK",
        "HTTP/1.1",
        Map.copyOf(normalizedHeaders),
        new HttpBodyView(new byte[0], StandardCharsets.UTF_8),
        timestamp);
  }
}
