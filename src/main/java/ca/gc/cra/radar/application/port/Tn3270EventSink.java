package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Event;

/**
 * <strong>What:</strong> Output port that persists or forwards TN3270 assembler events.
 * <p><strong>Why:</strong> Allows adapters (Kafka, file, etc.) to consume structured terminal activity without
 * coupling the assembler to a specific transport.</p>
 *
 * @since RADAR 0.2.0
 */
public interface Tn3270EventSink extends AutoCloseable {
  /**
   * Accepts an assembled TN3270 event.
   *
   * @param event immutable TN3270 event; must not be {@code null}
   * @throws Exception if the sink cannot persist or forward the event
   */
  void accept(Tn3270Event event) throws Exception;

  @Override
  default void close() throws Exception {}
}
