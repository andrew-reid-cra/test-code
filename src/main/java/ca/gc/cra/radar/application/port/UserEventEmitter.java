package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.events.UserEvent;

/**
 * <strong>What:</strong> Hexagonal port for emitting structured user events into telemetry or analytics sinks.
 * <p><strong>Why:</strong> Keeps rule evaluation decoupled from specific delivery transports (logging, Kafka, OTLP).</p>
 * <p><strong>Role:</strong> Outbound port implemented by adapters that forward {@link UserEvent} instances.</p>
 * <p><strong>Thread-safety:</strong> Implementations must be safe for concurrent invocations from capture threads.</p>
 * <p><strong>Performance:</strong> Calls should be non-blocking and O(1) amortized; adapters may batch asynchronously.</p>
 *
 * @since RADAR 1.1.0
 */
public interface UserEventEmitter extends AutoCloseable {
  /**
   * Emits a structured user event.
   *
   * @param event event payload; never {@code null}
   */
  void emit(UserEvent event);

  /**
   * Default no-op implementation for tests or disabled pipelines.
   */
  UserEventEmitter NO_OP = new UserEventEmitter() {
    @Override public void emit(UserEvent event) {}

    @Override public void close() {}
  };

  @Override
  default void close() {}
}
