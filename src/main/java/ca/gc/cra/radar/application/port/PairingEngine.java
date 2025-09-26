package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import java.util.Optional;

/**
 * <strong>What:</strong> Domain port that pairs protocol message events into request/response groupings.
 * <p><strong>Why:</strong> Provides flow-scoped pairing logic so assemble pipelines can emit complete exchanges.</p>
 * <p><strong>Role:</strong> Domain port implemented by protocol-specific adapters.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Buffer outstanding requests.</li>
 *   <li>Emit {@link MessagePair} objects when responses arrive.</li>
 *   <li>Surface pairing failures or timeouts to callers.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations typically maintain mutable state per flow and are not thread-safe.</p>
 * <p><strong>Performance:</strong> Expected to operate in O(1) per event using small queues.</p>
 * <p><strong>Observability:</strong> Implementations should emit metrics for pairing success, backlog, and timeouts.</p>
 *
 * @since 0.1.0
 * @see ca.gc.cra.radar.application.util.MessagePairer
 */
public interface PairingEngine {
  /**
   * Accepts a message event and emits a {@link MessagePair} when a counterpart is available.
   *
   * @param event message event produced by a reconstructor; may be {@code null}
   * @return paired message when ready; otherwise {@link Optional#empty()}
   * @throws Exception if pairing fails or the session enters an unrecoverable state
   *
   * <p><strong>Concurrency:</strong> Callers must serialize invocations per flow instance.</p>
   * <p><strong>Performance:</strong> Expected O(1) enqueue/dequeue work.</p>
   * <p><strong>Observability:</strong> Implementations generally track counters such as {@code assemble.pair.emitted}.</p>
   */
  Optional<MessagePair> accept(MessageEvent event) throws Exception;

  /**
   * Releases any resources associated with the pairing engine.
   *
   * @throws Exception if cleanup fails
   *
   * <p><strong>Concurrency:</strong> Call once when the flow shuts down.</p>
   * <p><strong>Performance:</strong> Constant time teardown.</p>
   * <p><strong>Observability:</strong> Implementations may log outstanding backlog on close.</p>
   */
  default void close() throws Exception {}
}
