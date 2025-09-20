package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.util.MessagePairer;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.Optional;

/** Host-first pairing: responses originate from host (server), requests from terminal (client). */
public final class Tn3270PairingEngineAdapter implements PairingEngine, AutoCloseable {
  private final MessagePairer delegate = new MessagePairer();

  @Override
  @Override
  public Optional<MessagePair> accept(MessageEvent event) throws Exception {
    boolean isRequest = event.type() == MessageType.REQUEST;
    return delegate.accept(event, isRequest);
  }

  @Override
  public void close() {
    delegate.close();
  }
}


