package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessagePair;

/**
 * Placeholder persistence adapter used until legacy sinks are migrated.
 * <p>Thread-safe and stateless; satisfies {@link PersistencePort} when persistence is disabled.
 *
 * @since RADAR 0.1-doc
 */
public final class NoOpPersistenceAdapter implements PersistencePort {
  /**
   * Creates a stateless adapter that drops all persistence requests.
   */
  public NoOpPersistenceAdapter() {}

  /**
   * Discards the provided pair.
   *
   * @param pair ignored; may be {@code null}
   * @implNote Callers typically replace this adapter with a real persistence sink.
   * @since RADAR 0.1-doc
   */
  @Override
  public void persist(MessagePair pair) {
    // Intentionally no-op for initial scaffolding.
  }
}



