package ca.gc.cra.radar.application.events;

import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.http.HttpRequestContext;
import ca.gc.cra.radar.application.events.http.HttpResponseContext;
import ca.gc.cra.radar.domain.events.AuthState;
import ca.gc.cra.radar.domain.events.SessionInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves session identifiers and authentication state from HTTP exchanges.
 *
 * @since RADAR 1.1.0
 */
public final class SessionResolver {
  private static final List<String> SESSION_COOKIE_CANDIDATES = List.of("jsessionid", "smsessionid");
  private static final List<String> SESSION_HEADER_CANDIDATES = List.of("jsessionid", "smsessionid");
  private static final long BUCKET_MICROS = Duration.ofMinutes(15).toMillis() * 1_000L;
  private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(SessionResolver::initSha256);
  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  /**
   * Resolves session metadata for the supplied exchange.
   *
   * @param exchange HTTP exchange; never {@code null}
   * @param extractedUserId optional user id derived from rule extraction
   * @return immutable session info
   */
  public SessionInfo resolve(HttpExchangeContext exchange, String extractedUserId) {
    Objects.requireNonNull(exchange, "exchange");
    HttpRequestContext request = exchange.request();
    HttpResponseContext response = exchange.response();

    SessionCandidate candidate = findCandidate(request, response);
    String userId = trimToNull(extractedUserId);
    boolean authenticated = candidate.trusted || userId != null;
    AuthState state = authenticated ? AuthState.AUTHENTICATED : AuthState.UNAUTHENTICATED;
    return new SessionInfo(candidate.sessionId(), userId, state);
  }

  private SessionCandidate findCandidate(HttpRequestContext request, HttpResponseContext response) {
    for (String candidate : SESSION_COOKIE_CANDIDATES) {
      String value = request.cookies().get(candidate);
      if (value != null && !value.isEmpty()) {
        return new SessionCandidate(value, true);
      }
    }

    if (response != null) {
      List<String> setCookies = response.headerValues("set-cookie");
      for (String candidate : SESSION_COOKIE_CANDIDATES) {
        Optional<String> value = findInSetCookie(setCookies, candidate);
        if (value.isPresent()) {
          return new SessionCandidate(value.get(), true);
        }
      }
    }

    for (String candidate : SESSION_HEADER_CANDIDATES) {
      Optional<String> header = request.header(candidate);
      if (header.isPresent()) {
        return new SessionCandidate(header.get(), true);
      }
    }

    return new SessionCandidate(deriveSessionId(request), false);
  }

  private Optional<String> findInSetCookie(List<String> setCookies, String candidate) {
    if (setCookies == null || setCookies.isEmpty()) {
      return Optional.empty();
    }
    String target = candidate.toLowerCase(Locale.ROOT);
    for (String header : setCookies) {
      if (header == null || header.isEmpty()) {
        continue;
      }
      int eq = header.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String name = header.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      if (!name.equals(target)) {
        continue;
      }
      int semicolon = header.indexOf(';', eq + 1);
      String value = semicolon > 0 ? header.substring(eq + 1, semicolon) : header.substring(eq + 1);
      value = value.trim();
      if (!value.isEmpty()) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private String deriveSessionId(HttpRequestContext request) {
    String clientIp = trimToEmpty(request.clientIp());
    String userAgent = request.header("user-agent").orElse("");
    long bucket = request.timestampMicros() <= 0 ? 0L : request.timestampMicros() / BUCKET_MICROS;
    MessageDigest digest = SHA256.get();
    digest.reset();
    digest.update(clientIp.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '|');
    digest.update(userAgent.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '|');
    digest.update(Long.toString(bucket).getBytes(StandardCharsets.UTF_8));
    byte[] hash = digest.digest();
    String hex = HEX.formatHex(hash, 0, 12);
    return "DERIVED-" + hex;
  }

  private static MessageDigest initSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private static String trimToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record SessionCandidate(String sessionId, boolean trusted) {}
}
