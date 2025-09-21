package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessagePair;

/**
 * Port that persists reconstructed protocol message pairs to durable storage or downstream systems.
 *
 * @since RADAR 0.1-doc
 */
public interface PersistencePort extends AutoCloseable {
  /**
   * Persists a single message pair.
   *
   * @param pair reconstructed request/response pair
   * @throws Exception if the operation fails
   * @since RADAR 0.1-doc
   */
  void persist(MessagePair pair) throws Exception;

  /**
   * Flushes buffered state to the underlying store.
   *
   * @throws Exception if flushing fails
   * @since RADAR 0.1-doc
   */
  default void flush() throws Exception {}

  /**
   * Closes the persistence resource.
   *
   * @throws Exception if shutdown fails
   * @since RADAR 0.1-doc
   */
  @Override
  default void close() throws Exception {}
}
