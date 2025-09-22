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

  public long position() {
    return position;
  }

  public void write(byte[] data, int offset, int length) throws IOException {
    if (length <= 0) {
      return;
    }
    stream.write(data, offset, length);
    position += length;
  }

  public void flush(boolean metadata) throws IOException {
    stream.flush();
    channel.force(metadata);
  }

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

