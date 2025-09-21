package ca.gc.cra.radar.infrastructure.persistence.segment;

import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.infrastructure.persistence.SegmentIoAdapter;
import java.nio.file.Path;

/**
 * File-based {@link SegmentPersistencePort} that writes captured segments using the SGB1 format.
 * <p>Not thread-safe; external synchronization required for concurrent use.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class SegmentFileSinkAdapter implements SegmentPersistencePort {
  private final SegmentIoAdapter.Writer writer;

  /**
   * Creates a new sink that rolls files after {@code rollMiB}.
   *
   * @param directory output directory
   * @param baseName file prefix for rolled files
   * @param rollMiB roll size in mebibytes; non-positive disables rolling
   * @throws IllegalStateException if the underlying writer cannot be created
   * @since RADAR 0.1-doc
   */
  public SegmentFileSinkAdapter(Path directory, String baseName, int rollMiB) {
    try {
      this.writer = new SegmentIoAdapter.Writer(directory, baseName, rollMiB);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create segment writer", e);
    }
  }

  /**
   * Persists the provided segment record.
   *
   * @param record record to persist; {@code null} is ignored
   * @throws Exception if writing fails
   * @since RADAR 0.1-doc
   */
  @Override
  public void persist(SegmentRecord record) throws Exception {
    if (record == null) return;
    writer.append(record);
  }

  /**
   * Flushes pending data to disk.
   *
   * @throws Exception if flushing fails
   * @since RADAR 0.1-doc
   */
  @Override
  public void flush() throws Exception {
    writer.flush();
  }

  /**
   * Closes the underlying writer.
   *
   * @throws Exception if closing fails
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() throws Exception {
    writer.close();
  }
}
