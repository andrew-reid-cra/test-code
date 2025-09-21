package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import java.util.Optional;

/**
 * Port that pairs protocol message events into request/response groupings.
 * <p>Implementations are typically stateful per flow and not thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
public interface PairingEngine {
  /**
   * Accepts a message event and, when a counterpart is available, emits a {@link MessagePair}.
   *
   * @param event message event produced by a reconstructor
   * @return paired message when ready; otherwise {@link Optional#empty()}
   * @throws Exception if pairing fails
   * @since RADAR 0.1-doc
   */
  Optional<MessagePair> accept(MessageEvent event) throws Exception;

  /**
   * Releases any resources associated with the pairing engine.
   *
   * @throws Exception if cleanup fails
   * @since RADAR 0.1-doc
   */
  default void close() throws Exception {}
}
