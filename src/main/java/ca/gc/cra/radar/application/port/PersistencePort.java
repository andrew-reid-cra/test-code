package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessagePair;

public interface PersistencePort extends AutoCloseable {
  void persist(MessagePair pair) throws Exception;

  default void flush() throws Exception {}

  @Override
  default void close() throws Exception {}
}


