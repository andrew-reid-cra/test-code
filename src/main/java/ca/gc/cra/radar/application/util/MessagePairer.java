package ca.gc.cra.radar.application.util;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.Optional;

/**
 * FIFO-based pairing helper that matches request and response message events.
 * <p>Not thread-safe; intended for per-flow use.</p>
 *
 * @since RADAR 0.1-doc
 */
public class MessagePairer extends AbstractMessageSession {
  /**
   * Accepts a message event and returns a {@link MessagePair} when a matching counterpart arrives.
   *
   * @param event message event to consider
   * @param isRequest {@code true} when the event should be buffered as a request
   * @return completed pair when a buffered request and new response align; otherwise empty
   * @since RADAR 0.1-doc
   */
  public Optional<MessagePair> accept(MessageEvent event, boolean isRequest) {
    if (event == null) return Optional.empty();
    if (isRequest) {
      enqueue(event);
      return Optional.empty();
    }
    Optional<MessageEvent> maybeRequest = dequeue();
    return maybeRequest.map(req -> new MessagePair(req, event));
  }

  /**
   * Flushes pending state when a response is expected but missing.
   *
   * @param type message type to flush; only {@link MessageType#RESPONSE} triggers output
   * @return synthetic pair containing the unmatched request when flushed; otherwise empty
   * @since RADAR 0.1-doc
   */
  public Optional<MessagePair> flush(MessageType type) {
    if (type == MessageType.RESPONSE) {
      Optional<MessageEvent> maybeRequest = dequeue();
      return maybeRequest.map(req -> new MessagePair(req, null));
    }
    return Optional.empty();
  }

  /**
   * Clears buffered state.
   *
   * @since RADAR 0.1-doc
   */
  public void close() {
    while (hasPending()) {
      dequeue();
    }
  }
}
