package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.capture.SegmentRecord;

public interface SegmentPersistencePort extends AutoCloseable {
  void persist(SegmentRecord record) throws Exception;

  default void flush() throws Exception {}

  @Override
  default void close() throws Exception {}
}
