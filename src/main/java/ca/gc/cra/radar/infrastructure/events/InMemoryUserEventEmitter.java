package ca.gc.cra.radar.infrastructure.events;

import ca.gc.cra.radar.application.port.UserEventEmitter;
import ca.gc.cra.radar.domain.events.UserEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory emitter used for tests and diagnostics.
 *
 * @since RADAR 1.1.0
 */
public final class InMemoryUserEventEmitter implements UserEventEmitter {
  private final CopyOnWriteArrayList<UserEvent> events = new CopyOnWriteArrayList<>();

  @Override
  public void emit(UserEvent event) {
    events.add(Objects.requireNonNull(event, "event"));
  }

  /**
   * Returns a snapshot of emitted events.
   *
   * @return immutable list of events
   */
  public List<UserEvent> snapshot() {
    return List.copyOf(events);
  }

  /**
   * Clears the captured events.
   */
  public void clear() {
    events.clear();
  }
}
