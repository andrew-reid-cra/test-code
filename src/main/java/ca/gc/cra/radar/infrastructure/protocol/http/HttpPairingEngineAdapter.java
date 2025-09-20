package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.util.MessagePairer;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.Objects;
import java.util.Optional;

/** Simple FIFO pairer assuming client -> request, server -> response semantics. */
public final class HttpPairingEngineAdapter implements PairingEngine, AutoCloseable {
  private final MessagePairer delegate = new MessagePairer();

  @Override
  public Optional<MessagePair> accept(MessageEvent event) throws Exception {
    Objects.requireNonNull(event, "event");
    boolean isRequest = event.type() == MessageType.REQUEST;
    return delegate.accept(event, isRequest);
  }

  @Override
  public void close() {
    delegate.close();
  }
}


