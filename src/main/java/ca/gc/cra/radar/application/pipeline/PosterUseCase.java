package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.PersistencePort;
import java.util.Objects;

public final class PosterUseCase {
  private final PersistencePort persistence;

  public PosterUseCase(PersistencePort persistence) {
    this.persistence = Objects.requireNonNull(persistence);
  }

  public void run() throws Exception {
    throw new UnsupportedOperationException("Poster pipeline not yet migrated to ports");
  }
}


