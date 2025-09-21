package ca.gc.cra.radar.application.util;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Base class for protocol sessions that maintain request/response ordering state.
 * <p>Not thread-safe; subclasses should ensure single-threaded access.</p>
 *
 * @since RADAR 0.1-doc
 */
public abstract class AbstractMessageSession {
  private final Deque<MessageEvent> pending = new ArrayDeque<>();

  /**
   * Creates a new session with an empty pending queue.
   *
   * @since RADAR 0.1-doc
   */
  protected AbstractMessageSession() {}

  /**
   * Enqueues a message event awaiting pairing.
   *
   * @param event message event to buffer; never {@code null}
   * @since RADAR 0.1-doc
   */
  protected void enqueue(MessageEvent event) {
    pending.addLast(event);
  }

  /**
   * Removes the next pending message event if available.
   *
   * @return oldest buffered event, or {@link Optional#empty()} when none remain
   * @since RADAR 0.1-doc
   */
  protected Optional<MessageEvent> dequeue() {
    return pending.isEmpty() ? Optional.empty() : Optional.of(pending.removeFirst());
  }

  /**
   * Indicates whether buffered events remain.
   *
   * @return {@code true} when at least one message is waiting to be paired
   * @since RADAR 0.1-doc
   */
  protected boolean hasPending() {
    return !pending.isEmpty();
  }
}
