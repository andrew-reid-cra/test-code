package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.infrastructure.persistence.segment.SegmentBinIO;
import java.io.IOException;
import java.nio.file.Path;

public final class SegmentIoAdapter {
  private SegmentIoAdapter() {}

  public static final class Writer implements AutoCloseable {
    private final SegmentBinIO.Writer delegate;

    public Writer(Path directory, String baseName, int rollMiB) throws IOException {
      this.delegate = new SegmentBinIO.Writer(directory, baseName, rollMiB);
    }

    public void append(SegmentRecord record) throws Exception {
      delegate.append(record);
    }

    public void flush() throws Exception {
      delegate.flush();
    }

    @Override
    public void close() throws Exception {
      delegate.close();
    }
  }

  public static final class Reader implements AutoCloseable {
    private final SegmentBinIO.Reader delegate;

    public Reader(Path directory) throws IOException {
      this.delegate = new SegmentBinIO.Reader(directory);
    }

    public SegmentRecord next() throws Exception {
      return delegate.next();
    }

    @Override
    public void close() throws Exception {
      delegate.close();
    }
  }
}
