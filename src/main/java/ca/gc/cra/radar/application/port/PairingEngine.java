package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import java.util.Optional;

public interface PairingEngine {
  Optional<MessagePair> accept(MessageEvent event) throws Exception;

  default void close() throws Exception {}
}
