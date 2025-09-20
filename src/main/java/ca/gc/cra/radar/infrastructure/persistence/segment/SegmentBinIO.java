package ca.gc.cra.radar.infrastructure.persistence.segment;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Minimal segment file format compatible with legacy segbin (SGB1) files.
 */
public final class SegmentBinIO {
  private static final byte[] MAGIC = {'S', 'G', 'B', '1'};
  private static final int HEADER_LEN = 4;

  private SegmentBinIO() {}

  public static final class Writer implements Closeable, Flushable {
    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path directory;
    private final String baseName;
    private final long rollBytes;

    private CountingOutputStream countingOut;
    private DataOutputStream dataOut;
    private int partIndex = 0;

    public Writer(Path directory, String baseName, int rollMiB) throws IOException {
      this.directory = directory;
      this.baseName = baseName;
      this.rollBytes = rollMiB <= 0 ? Long.MAX_VALUE : rollMiB * 1024L * 1024L;
      Files.createDirectories(directory);
      openNewFile();
    }

    public void append(SegmentRecord record) throws IOException {
      if (record == null) {
        return;
      }
      byte[] payload = record.payload();
      int payloadLen = payload != null ? payload.length : 0;
      byte[] srcBytes = record.srcIp().getBytes(StandardCharsets.UTF_8);
      byte[] dstBytes = record.dstIp().getBytes(StandardCharsets.UTF_8);
      int recordLen = 8 + 2 + srcBytes.length + 4 + 2 + dstBytes.length + 4 + 8 + 4 + 4 + payloadLen;

      if (rollBytes != Long.MAX_VALUE && countingOut.getCount() + 4L + recordLen > rollBytes) {
        openNewFile();
      }

      dataOut.writeInt(recordLen);
      dataOut.writeLong(record.timestampMicros());
      dataOut.writeUTF(record.srcIp());
      dataOut.writeInt(record.srcPort());
      dataOut.writeUTF(record.dstIp());
      dataOut.writeInt(record.dstPort());
      dataOut.writeLong(record.sequence());
      dataOut.writeInt(record.flags());
      dataOut.writeInt(payloadLen);
      if (payloadLen > 0) {
        dataOut.write(payload, 0, payloadLen);
      }
    }

    private void openNewFile() throws IOException {
      close();
      String timestamp = TS.format(Instant.now());
      String fileName = String.format("%s-%s-%04d.segbin", baseName, timestamp, partIndex++);
      Path path = directory.resolve(fileName);
      countingOut = new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 20));
      dataOut = new DataOutputStream(countingOut);
      dataOut.write(MAGIC);
      dataOut.flush();
    }

    @Override
    public void flush() throws IOException {
      if (dataOut != null) {
        dataOut.flush();
      }
    }

    @Override
    public void close() throws IOException {
      if (dataOut != null) {
        try {
          dataOut.flush();
        } finally {
          dataOut.close();
          dataOut = null;
          countingOut = null;
        }
      }
    }
  }

  public static final class Reader implements Closeable {
    private final List<Path> files;
    private int index = 0;
    private DataInputStream in;

    public Reader(Path directory) throws IOException {
      if (!Files.isDirectory(directory)) {
        throw new IOException("Not a directory: " + directory);
      }
      try (Stream<Path> stream = Files.list(directory)) {
        files = stream
            .filter(p -> p.getFileName().toString().endsWith(".segbin"))
            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
            .collect(Collectors.toList());
      }
      openNext();
    }

    public SegmentRecord next() throws IOException {
      while (true) {
        if (in == null) {
          return null;
        }
        try {
          int recordLen = in.readInt();
          long ts = in.readLong();
          String src = in.readUTF();
          int sport = in.readInt();
          String dst = in.readUTF();
          int dport = in.readInt();
          long seq = in.readLong();
          int flags = in.readInt();
          int payloadLen = in.readInt();
          byte[] payload = new byte[Math.max(0, payloadLen)];
          if (payloadLen > 0) {
            in.readFully(payload, 0, payloadLen);
          }
          return new SegmentRecord(ts, src, sport, dst, dport, seq, flags, payload);
        } catch (EOFException eof) {
          openNext();
        }
      }
    }

    private void openNext() throws IOException {
      closeStream();
      if (index >= files.size()) {
        return;
      }
      Path next = files.get(index++);
      in = new DataInputStream(new BufferedInputStream(Files.newInputStream(next), 1 << 20));
      byte[] header = new byte[HEADER_LEN];
      int read = in.read(header);
      if (read != HEADER_LEN || !Arrays.equals(header, MAGIC)) {
        throw new IOException("Invalid segment header: " + next);
      }
    }

    private void closeStream() throws IOException {
      if (in != null) {
        in.close();
        in = null;
      }
    }

    @Override
    public void close() throws IOException {
      closeStream();
    }
  }

  private static final class CountingOutputStream extends FilterOutputStream {
    private long count;

    CountingOutputStream(OutputStream out) {
      super(out);
    }

    long getCount() {
      return count;
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      count += len;
    }
  }
}


