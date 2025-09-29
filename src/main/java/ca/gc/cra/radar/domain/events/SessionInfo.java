package ca.gc.cra.radar.domain.events;

import java.util.Objects;

/**
 * Immutable session metadata resolved while interpreting reconstructed HTTP exchanges.
 *
 * <p><strong>Why:</strong> Downstream analytics and telemetry require a stable session identifier and authentication state.</p>
 * <p><strong>Thread-safety:</strong> Records are immutable and safely shareable.</p>
 *
 * @param sessionId canonical session identifier; never {@code null}
 * @param userId optional user identifier associated with the session; may be {@code null}
 * @param authState authentication state resolved for the session; never {@code null}
 *
 * @since RADAR 1.1.0
 */
public record SessionInfo(String sessionId, String userId, AuthState authState) {

  /**
   * Canonicalizes constructor arguments to enforce non-null invariants.
   */
  public SessionInfo {
    sessionId = Objects.requireNonNull(sessionId, "sessionId");
    authState = Objects.requireNonNull(authState, "authState");
    userId = normalize(userId);
  }

  private static String normalize(String candidate) {
    if (candidate == null) {
      return null;
    }
    String trimmed = candidate.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Builds an unauthenticated session record with the supplied session identifier.
   *
   * @param sessionId session identifier; never {@code null}
   * @return immutable session info marked {@link AuthState#UNAUTHENTICATED}
   */
  public static SessionInfo unauthenticated(String sessionId) {
    return new SessionInfo(sessionId, null, AuthState.UNAUTHENTICATED);
  }

  /**
   * Builds an authenticated session record with the supplied identifiers.
   *
   * @param sessionId session identifier; never {@code null}
   * @param userId optional user identifier associated with the session
   * @return immutable session info marked {@link AuthState#AUTHENTICATED}
   */
  public static SessionInfo authenticated(String sessionId, String userId) {
    return new SessionInfo(sessionId, userId, AuthState.AUTHENTICATED);
  }
}
