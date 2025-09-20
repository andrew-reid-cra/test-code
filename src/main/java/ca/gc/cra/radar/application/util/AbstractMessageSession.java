package ca.gc.cra.radar.application.util;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Base class for protocol sessions that correlates request/response ordering. Concrete subclasses
 * decide how to classify events as requests or responses.
 */
public abstract class AbstractMessageSession {
  private final Deque<MessageEvent> pending = new ArrayDeque<>();

  protected void enqueue(MessageEvent event) {
    pending.addLast(event);
  }

  protected Optional<MessageEvent> dequeue() {
    return pending.isEmpty() ? Optional.empty() : Optional.of(pending.removeFirst());
  }

  protected boolean hasPending() {
    return !pending.isEmpty();
  }
}


