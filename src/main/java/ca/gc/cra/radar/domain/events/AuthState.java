package ca.gc.cra.radar.domain.events;

/**
 * Authentication state associated with a reconstructed user-facing HTTP session.
 *
 * <p><strong>Why:</strong> User event analytics distinguish between authenticated and anonymous activity.
 * <p><strong>Thread-safety:</strong> Enum constants are immutable and safe to share.
 *
 * @since RADAR 1.1.0
 */
public enum AuthState {
  /** Authenticated user context resolved from principals or session cookies. */
  AUTHENTICATED,

  /** No authenticated principal or session cookie could be resolved. */
  UNAUTHENTICATED;
}
