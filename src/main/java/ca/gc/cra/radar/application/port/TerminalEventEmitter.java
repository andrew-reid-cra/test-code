package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.events.TerminalEvent;

/**
 * <strong>What:</strong> Outbound hexagonal port for emitting structured terminal interaction events into
 * observability or persistence pipelines.
 * <p><strong>Why:</strong> Keeps domain logic decoupled from delivery transport concerns while enabling adapters
 * such as logging, Kafka, or OTLP exporters.</p>
 * <p><strong>Thread-safety:</strong> Implementations must tolerate concurrent invocations from capture threads.</p>
 * <p><strong>Performance:</strong> Calls should be non-blocking and constant-time; adapters may buffer asynchronously.</p>
 *
 * @since RADAR 1.2.0
 */
public interface TerminalEventEmitter extends AutoCloseable {
  /**
   * Emits a structured terminal event.
   *
   * @param event immutable event payload; never {@code null}
   */
  void emit(TerminalEvent event);

  /**
   * Default no-op implementation for tests or disabled pipelines.
   */
  TerminalEventEmitter NO_OP = new TerminalEventEmitter() {
    @Override public void emit(TerminalEvent event) {}

    @Override public void close() {}
  };

  @Override
  default void close() {}
}