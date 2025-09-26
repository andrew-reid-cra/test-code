package ca.gc.cra.radar.application.util;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.Optional;

/**
 * <strong>What:</strong> FIFO-based helper that pairs request and response {@link MessageEvent}s.
 * <p><strong>Why:</strong> Assemble-stage protocol adapters use this to construct {@link MessagePair}s for sink pipelines.</p>
 * <p><strong>Role:</strong> Domain support utility layered on {@link AbstractMessageSession}.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Buffer outstanding request events.</li>
 *   <li>Emit {@link MessagePair} objects when a matching response arrives.</li>
 *   <li>Flush orphaned requests when flows terminate without responses.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Not thread-safe; confine each instance to a single protocol flow.</p>
 * <p><strong>Performance:</strong> O(1) operations on a small deque; no extra allocations beyond pairs.</p>
 * <p><strong>Observability:</strong> Delegates metrics/logging to callers; returns {@link Optional} to signal pairing.</p>
 *
 * @implNote Does not inspect payloads; pairing relies on caller-provided {@code isRequest} flags.
 * @since 0.1.0
 */
public class MessagePairer extends AbstractMessageSession {
  /**
   * Accepts a message event and returns a {@link MessagePair} when a counterpart is available.
   *
   * @param event message event to consider; {@code null} values are ignored and yield {@link Optional#empty()}
   * @param isRequest {@code true} when the event is a request that should be buffered until a response arrives
   * @return completed pair when a buffered request and new response align; otherwise {@link Optional#empty()}
   *
   * <p><strong>Concurrency:</strong> Caller must invoke from a single flow thread.</p>
   * <p><strong>Performance:</strong> O(1) enqueue or dequeue; allocates only when producing a pair.</p>
   * <p><strong>Observability:</strong> Callers should emit metrics (e.g., {@code assemble.pair.success}) based on the return value.</p>
   */
  public Optional<MessagePair> accept(MessageEvent event, boolean isRequest) {
    if (event == null) {
      return Optional.empty();
    }
    if (isRequest) {
      enqueue(event);
      return Optional.empty();
    }
    Optional<MessageEvent> maybeRequest = dequeue();
    return maybeRequest.map(req -> new MessagePair(req, event));
  }

  /**
   * Flushes pending state when a response should have arrived but did not.
   *
   * @param type message type causing the flush; only {@link MessageType#RESPONSE} triggers output
   * @return synthetic pair containing the unmatched request when flushed; otherwise {@link Optional#empty()}
   *
   * <p><strong>Concurrency:</strong> Caller must invoke from the same thread as {@link #accept(MessageEvent, boolean)}.</p>
   * <p><strong>Performance:</strong> O(1) deque operation; allocates only when emitting a synthetic pair.</p>
   * <p><strong>Observability:</strong> Callers should log or count flushes under metrics such as {@code assemble.pair.timeout}.</p>
   */
  public Optional<MessagePair> flush(MessageType type) {
    if (type == MessageType.RESPONSE) {
      Optional<MessageEvent> maybeRequest = dequeue();
      return maybeRequest.map(req -> new MessagePair(req, null));
    }
    return Optional.empty();
  }

  /**
   * Clears buffered state, discarding unmatched requests.
   *
   * <p><strong>Concurrency:</strong> Confine to the owning flow thread.</p>
   * <p><strong>Performance:</strong> Iteratively dequeues pending elements; cost proportional to backlog size.</p>
   * <p><strong>Observability:</strong> Callers may record the number of discarded requests.</p>
   */
  public void close() {
    while (hasPending()) {
      dequeue();
    }
  }
}

