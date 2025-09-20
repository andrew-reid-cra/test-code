package ca.gc.cra.radar.application.util;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.Optional;

/**
 * Simple FIFO pairing helper; protocols may extend/replace with custom semantics.
 */
public class MessagePairer extends AbstractMessageSession {
  public Optional<MessagePair> accept(MessageEvent event, boolean isRequest) {
    if (event == null) return Optional.empty();
    if (isRequest) {
      enqueue(event);
      return Optional.empty();
    }
    Optional<MessageEvent> maybeRequest = dequeue();
    return maybeRequest.map(req -> new MessagePair(req, event));
  }

  public Optional<MessagePair> flush(MessageType type) {
    if (type == MessageType.RESPONSE) {
      Optional<MessageEvent> maybeRequest = dequeue();
      return maybeRequest.map(req -> new MessagePair(req, null));
    }
    return Optional.empty();
  }

  public void close() {
    while (hasPending()) {
      dequeue();
    }
  }
}
