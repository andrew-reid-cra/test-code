package ca.gc.cra.radar.application.util;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * <strong>What:</strong> Base class that tracks request/response ordering for message-oriented protocols.
 * <p><strong>Why:</strong> Assemble-stage adapters reuse this FIFO session state to pair messages before handing off to sinks.</p>
 * <p><strong>Role:</strong> Domain support utility extending {@link MessagePairer} and similar components.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Maintain a FIFO queue of pending {@link MessageEvent}s.</li>
 *   <li>Provide enqueue/dequeue helpers for subclasses.</li>
 *   <li>Expose instrumentation hooks (protected accessors) for flush logic.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Not thread-safe; confine each instance to a single flow/thread.</p>
 * <p><strong>Performance:</strong> Uses {@link ArrayDeque} for O(1) enqueue/dequeue with minimal allocations.</p>
 * <p><strong>Observability:</strong> No direct metrics; subclasses emit pairing counters.</p>
 *
 * @implNote Queue growth is bounded by the number of outstanding requests in a flow.
 * @since 0.1.0
 */
public abstract class AbstractMessageSession {
  private final Deque<MessageEvent> pending = new ArrayDeque<>();

  /**
   * Creates a new session with an empty pending queue.
   *
   * <p><strong>Concurrency:</strong> Call from a single thread during flow initialization.</p>
   * <p><strong>Performance:</strong> Constant time; allocates the backing deque.</p>
   * <p><strong>Observability:</strong> No metrics.</p>
   */
  protected AbstractMessageSession() {}

  /**
   * Enqueues a message event awaiting pairing.
   *
   * @param event message event to buffer; must not be {@code null}
   *
   * <p><strong>Concurrency:</strong> Call from the owning flow thread.</p>
   * <p><strong>Performance:</strong> O(1) addition to the tail of the deque.</p>
   * <p><strong>Observability:</strong> Subclasses may increment pairing backlog metrics after calling.</p>
   */
  protected void enqueue(MessageEvent event) {
    pending.addLast(event);
  }

  /**
   * Removes the next pending message event if available.
   *
   * @return oldest buffered event, or {@link Optional#empty()} when none remain
   *
   * <p><strong>Concurrency:</strong> Call from the owning flow thread.</p>
   * <p><strong>Performance:</strong> O(1) removal from the head of the deque.</p>
   * <p><strong>Observability:</strong> Subclasses may observe queue depth after dequeues.</p>
   */
  protected Optional<MessageEvent> dequeue() {
    return pending.isEmpty() ? Optional.empty() : Optional.of(pending.removeFirst());
  }

  /**
   * Indicates whether buffered events remain.
   *
   * @return {@code true} when at least one message is waiting to be paired
   *
   * <p><strong>Concurrency:</strong> Call from the owning flow thread.</p>
   * <p><strong>Performance:</strong> Constant time size check.</p>
   * <p><strong>Observability:</strong> No metrics.</p>
   */
  protected boolean hasPending() {
    return !pending.isEmpty();
  }
}

