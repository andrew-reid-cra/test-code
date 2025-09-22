package ca.gc.cra.radar.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class SegmentIoAdapterTest {
  @Test
  void writeAndReadRoundTrip() throws Exception {
    Path dir = Files.createTempDirectory("seg-io");
    SegmentRecord record =
        new SegmentRecord(1L, "1.1.1.1", 1234, "2.2.2.2", 80, 10L, SegmentRecord.ACK, new byte[] {1, 2});

    try (SegmentIoAdapter.Writer writer = new SegmentIoAdapter.Writer(dir, "test", 1)) {
      writer.append(record);
      writer.flush();
    }

    try (SegmentIoAdapter.Reader reader = new SegmentIoAdapter.Reader(dir)) {
      SegmentRecord read = reader.next();
      assertNotNull(read);
      assertEquals(record.srcIp(), read.srcIp());
      assertArrayEquals(record.payload(), read.payload());
    }
  }
}



