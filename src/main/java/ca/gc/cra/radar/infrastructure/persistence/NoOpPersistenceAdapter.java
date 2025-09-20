package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.msg.MessagePair;

/** Placeholder persistence adapter used until legacy sinks are migrated. */
public final class NoOpPersistenceAdapter implements PersistencePort {
  @Override
  public void persist(MessagePair pair) {
    // Intentionally no-op for initial scaffolding.
  }
}


