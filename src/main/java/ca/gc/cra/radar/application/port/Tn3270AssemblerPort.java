package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessagePair;

/**
 * <strong>What:</strong> Application port that transforms TN3270 message pairs into structured events.
 * <p><strong>Why:</strong> Decouples protocol-specific assembly logic from downstream sinks.</p>
 * <p><strong>Role:</strong> Consumes paired telnet records and emits higher-level TN3270 domain events via
 * {@link Tn3270EventSink} implementations.</p>
 *
 * @since RADAR 0.2.0
 */
public interface Tn3270AssemblerPort extends AutoCloseable {
  /**
   * Processes a reconstructed TN3270 message pair.
   *
   * @param pair message pair emitted by the pairing engine; may be {@code null}
   * @throws Exception if parsing fails or downstream sinks reject the event
   */
  void onPair(MessagePair pair) throws Exception;

  @Override
  void close() throws Exception;
}
