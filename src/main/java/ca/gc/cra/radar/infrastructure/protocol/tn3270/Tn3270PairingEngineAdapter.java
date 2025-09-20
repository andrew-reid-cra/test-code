package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/** Host-first pairing for TN3270 where the host response typically precedes the terminal request. */
public final class Tn3270PairingEngineAdapter implements PairingEngine, AutoCloseable {
  private final Deque<MessageEvent> pendingRequests = new ArrayDeque<>();
  private final Deque<MessageEvent> pendingResponses = new ArrayDeque<>();

  @Override
  public synchronized Optional<MessagePair> accept(MessageEvent event) {
    if (event == null) {
      return Optional.empty();
    }
    MessageType type = Objects.requireNonNull(event.type(), "event.type");
    return switch (type) {
      case REQUEST -> onRequest(event);
      case RESPONSE -> onResponse(event);
      default -> Optional.empty();
    };
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

  @Override
  public synchronized void close() {
    pendingRequests.clear();
    pendingResponses.clear();
  }
}
