package ca.gc.cra.radar.infrastructure.persistence.segment;

import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.infrastructure.persistence.SegmentIoAdapter;
import java.nio.file.Path;

public final class SegmentFileSinkAdapter implements SegmentPersistencePort {
  private final SegmentIoAdapter.Writer writer;

  public SegmentFileSinkAdapter(Path directory, String baseName, int rollMiB) {
    try {
      this.writer = new SegmentIoAdapter.Writer(directory, baseName, rollMiB);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create segment writer", e);
    }
  }

  @Override
  public void persist(SegmentRecord record) throws Exception {
    if (record == null) return;
    writer.append(record);
  }

  @Override
  public void flush() throws Exception {
    writer.flush();
  }

  @Override
  public void close() throws Exception {
    writer.close();
  }
}
