package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.infrastructure.persistence.segment.SegmentBinIO;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Convenience wrapper for reading and writing RADAR segment binary files.
 *
 * @since RADAR 0.1-doc
 */
public final class SegmentIoAdapter {
  private SegmentIoAdapter() {}

  /**
   * File writer for {@link SegmentRecord} sequences using {@link SegmentBinIO}.
   *
   * @since RADAR 0.1-doc
   */
  public static final class Writer implements AutoCloseable {
    private final SegmentBinIO.Writer delegate;

    /**
     * Creates a writer that rolls files at the specified size.
     *
     * @param directory output directory
     * @param baseName file prefix for rolled files
     * @param rollMiB roll size in mebibytes
     * @throws IOException if the writer cannot be opened
     * @since RADAR 0.1-doc
     */
    public Writer(Path directory, String baseName, int rollMiB) throws IOException {
      this.delegate = new SegmentBinIO.Writer(directory, baseName, rollMiB);
    }

    /**
     * Appends a segment record to the current file.
     *
     * @param record segment record to write; must not be {@code null}
     * @throws Exception if writing fails
     * @since RADAR 0.1-doc
     */
    public void append(SegmentRecord record) throws Exception {
      delegate.append(record);
    }

    /**
     * Flushes buffered records to disk.
     *
     * @throws Exception if flushing fails
     * @since RADAR 0.1-doc
     */
    public void flush() throws Exception {
      delegate.flush();
    }

    /**
     * Closes the underlying writer.
     *
     * @throws Exception if closing fails
     * @since RADAR 0.1-doc
     */
    @Override
    public void close() throws Exception {
      delegate.close();
    }
  }

  /**
   * File reader for {@link SegmentRecord} sequences using {@link SegmentBinIO}.
   *
   * @since RADAR 0.1-doc
   */
  public static final class Reader implements AutoCloseable {
    private final SegmentBinIO.Reader delegate;

    /**
     * Creates a reader bound to the given directory.
     *
     * @param directory directory containing segment files
     * @throws IOException if the reader cannot be opened
     * @since RADAR 0.1-doc
     */
    public Reader(Path directory) throws IOException {
      this.delegate = new SegmentBinIO.Reader(directory);
    }

    /**
     * Returns the next segment record or {@code null} at EOF.
     *
     * @return next segment record, or {@code null} when exhausted
     * @throws Exception if reading fails
     * @since RADAR 0.1-doc
     */
    public SegmentRecord next() throws Exception {
      return delegate.next();
    }

    /**
     * Closes the underlying reader.
     *
     * @throws Exception if closing fails
     * @since RADAR 0.1-doc
     */
    @Override
    public void close() throws Exception {
      delegate.close();
    }
  }
}
