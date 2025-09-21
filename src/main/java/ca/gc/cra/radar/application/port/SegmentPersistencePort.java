package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.capture.SegmentRecord;

/**
 * Port that stores captured TCP segment records for later processing.
 *
 * @since RADAR 0.1-doc
 */
public interface SegmentPersistencePort extends AutoCloseable {
  /**
   * Persists a captured segment record.
   *
   * @param record segment metadata and payload
   * @throws Exception if persistence fails
   * @since RADAR 0.1-doc
   */
  void persist(SegmentRecord record) throws Exception;

  /**
   * Flushes buffered records to durable storage.
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
