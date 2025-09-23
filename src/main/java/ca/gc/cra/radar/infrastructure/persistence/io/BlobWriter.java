package ca.gc.cra.radar.infrastructure.persistence.io;

import ca.gc.cra.radar.infrastructure.buffer.BufferPools;
import ca.gc.cra.radar.infrastructure.buffer.PooledBufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * File-backed blob writer with pooled buffering to minimize allocation churn.
 */
public final class BlobWriter implements AutoCloseable {
  private final FileChannel channel;
  private final PooledBufferedOutputStream stream;
  private long position;

  /**
   * Opens (or creates) a blob file, appending to the existing content if present.
   *
   * @param path target file path
   * @throws IOException when the file cannot be opened for append
   */
  public BlobWriter(Path path) throws IOException {
    this.channel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
    long size = channel.size();
    channel.position(size);
    this.position = size;
    OutputStream out = Channels.newOutputStream(channel);
    this.stream = new PooledBufferedOutputStream(out, BufferPools.ioBuffers());
  }

  /**
   * Returns the current write position within the blob.
   *
   * @return byte offset for the next write
   */
  public long position() {
    return position;
  }

  /**
   * Writes a slice of the provided array into the blob.
   *
   * @param data source buffer
   * @param offset starting offset within {@code data}
   * @param length number of bytes to write
   * @throws IOException when the write fails
   */
  public void write(byte[] data, int offset, int length) throws IOException {
    if (length <= 0) {
      return;
    }
    stream.write(data, offset, length);
    position += length;
  }

  /**
   * Flushes buffered bytes to disk and optionally forces filesystem metadata.
   *
   * @param metadata when {@code true}, forces an fsync of metadata as well as data
   * @throws IOException when the flush fails
   */
  public void flush(boolean metadata) throws IOException {
    stream.flush();
    channel.force(metadata);
  }

  /**
   * Flushes and releases all resources associated with this writer.
   *
   * @throws IOException when flushing or closing the underlying channel fails
   */
  @Override
  public void close() throws IOException {
    try {
      stream.flush();
      channel.force(true);
    } finally {
      stream.close();
    }
  }
}

