package ca.gc.cra.radar.infrastructure.buffer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * OutputStream wrapper that buffers writes using a pooled byte array.
 */
public final class PooledBufferedOutputStream extends OutputStream {
  private final OutputStream delegate;
  private final BufferPool.PooledBuffer pooled;
  private final byte[] buffer;
  private int position;
  private boolean closed;

  public PooledBufferedOutputStream(OutputStream delegate, BufferPool pool) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    Objects.requireNonNull(pool, "pool");
    this.pooled = pool.acquire();
    this.buffer = pooled.array();
    this.position = 0;
  }

  @Override
  public synchronized void write(int b) throws IOException {
    ensureOpen();
    if (position == buffer.length) {
      flushBuffer();
    }
    buffer[position++] = (byte) b;
  }

  @Override
  public synchronized void write(byte[] b, int off, int len) throws IOException {
    ensureOpen();
    if (len <= 0) {
      return;
    }
    Objects.checkFromIndexSize(off, len, b.length);
    int remaining = len;
    int offset = off;
    while (remaining > 0) {
      if (position == buffer.length) {
        flushBuffer();
      }
      int chunk = Math.min(buffer.length - position, remaining);
      System.arraycopy(b, offset, buffer, position, chunk);
      position += chunk;
      offset += chunk;
      remaining -= chunk;
      if (position == buffer.length) {
        flushBuffer();
      }
    }
  }

  @Override
  public synchronized void flush() throws IOException {
    ensureOpen();
    flushBuffer();
    delegate.flush();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    IOException error = null;
    try {
      flushBuffer();
    } catch (IOException flushError) {
      error = flushError;
    }
    try {
      delegate.close();
    } catch (IOException closeError) {
      if (error == null) {
        error = closeError;
      }
    } finally {
      closed = true;
      pooled.close();
    }
    if (error != null) {
      throw error;
    }
  }

  private void flushBuffer() throws IOException {
    if (position == 0) {
      return;
    }
    delegate.write(buffer, 0, position);
    position = 0;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("stream closed");
    }
  }
}
