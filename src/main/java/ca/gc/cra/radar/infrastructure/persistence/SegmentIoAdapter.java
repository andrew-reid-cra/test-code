package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.infrastructure.persistence.legacy.SegmentRecordMapper;
import java.io.IOException;
import java.nio.file.Path;

public final class SegmentIoAdapter {
  private SegmentIoAdapter() {}

  public static final class Writer implements AutoCloseable {
    private final sniffer.pipe.SegmentIO.Writer delegate;

    public Writer(Path directory, String baseName, int rollMiB) throws IOException {
      this.delegate = new sniffer.pipe.SegmentIO.Writer(directory, baseName, rollMiB);
    }

    public void append(SegmentRecord record) throws Exception {
      delegate.append(SegmentRecordMapper.toLegacy(record));
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
    private final sniffer.pipe.SegmentIO.Reader delegate;

    public Reader(Path directory) throws IOException {
      this.delegate = new sniffer.pipe.SegmentIO.Reader(directory);
    }

    public SegmentRecord next() throws Exception {
      sniffer.pipe.SegmentRecord legacy = delegate.next();
      if (legacy == null) {
        return null;
      }
      return SegmentRecordMapper.fromLegacy(legacy);
    }

    @Override
    public void close() throws Exception {
      delegate.close();
    }
  }
}


