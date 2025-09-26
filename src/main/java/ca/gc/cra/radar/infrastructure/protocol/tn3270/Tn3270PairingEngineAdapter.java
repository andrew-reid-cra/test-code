package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-first pairing for TN3270 where the host response typically precedes the terminal request.
 * <p>Synchronizes access because pairing state uses shared queues.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class Tn3270PairingEngineAdapter implements PairingEngine, AutoCloseable {
  private final Deque<MessageEvent> pendingRequests = new ArrayDeque<>();
  private final Deque<MessageEvent> pendingResponses = new ArrayDeque<>();

  /**
   * Creates a TN3270 pairing engine adapter with host-first semantics.
   *
   * @since RADAR 0.1-doc
   */
  public Tn3270PairingEngineAdapter() {}

  /**
   * Pairs TN3270 events into request/response tuples.
   *
   * @param event message event to pair; {@code null} is ignored
   * @return completed pair when available; otherwise empty
   * @throws NullPointerException if {@code event.type()} is {@code null}
   * @implNote Responses arriving before requests are queued until the matching request arrives.
   * @since RADAR 0.1-doc
   */
  @Override
  public synchronized Optional<MessagePair> accept(MessageEvent event) {
    if (event == null) {
      return Optional.empty();
    }
    MessageType type = Objects.requireNonNull(event.type(), "event.type");
    if (type == MessageType.REQUEST) {
      return onRequest(event);
    }
    if (type == MessageType.RESPONSE) {
      return onResponse(event);
    }
    return Optional.empty();
  }

  private Optional<MessagePair> onRequest(MessageEvent request) {
    if (!pendingResponses.isEmpty()) {
      MessageEvent response = pendingResponses.removeFirst();
      return Optional.of(new MessagePair(request, response));
    }
    pendingRequests.addLast(request);
    return Optional.empty();
  }

  private Optional<MessagePair> onResponse(MessageEvent response) {
    if (!pendingRequests.isEmpty()) {
      MessageEvent request = pendingRequests.removeFirst();
      return Optional.of(new MessagePair(request, response));
    }
    pendingResponses.addLast(response);
    return Optional.empty();
  }

  /**
   * Releases pairing resources by clearing queued requests and responses.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public synchronized void close() {
    pendingRequests.clear();
    pendingResponses.clear();
  }
}

