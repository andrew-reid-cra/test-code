package ca.gc.cra.radar.infrastructure.buffer;

import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple bounded pool of reusable byte arrays to minimize temporary allocations in IO hot paths.
 */
public final class BufferPool {
  private final int bufferSize;
  private final int maxPoolSize;
  private final ArrayDeque<byte[]> pool;
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Creates a buffer pool with the desired buffer size and maximum cached entries.
   *
   * @param bufferSize size of each pooled buffer in bytes
   * @param maxPoolSize maximum number of buffers kept in the pool
   */
  public BufferPool(int bufferSize, int maxPoolSize) {
    if (bufferSize <= 0) {
      throw new IllegalArgumentException("bufferSize must be positive");
    }
    if (maxPoolSize <= 0) {
      throw new IllegalArgumentException("maxPoolSize must be positive");
    }
    this.bufferSize = bufferSize;
    this.maxPoolSize = maxPoolSize;
    this.pool = new ArrayDeque<>(maxPoolSize);
  }

  /**
   * Borrows a buffer from the pool, creating one when the pool is empty.
   *
   * @return pooled buffer wrapper ready for use
   */
  public PooledBuffer acquire() {
    byte[] data;
    lock.lock();
    try {
      data = pool.pollFirst();
    } finally {
      lock.unlock();
    }
    if (data == null) {
      data = new byte[bufferSize];
    }
    return new PooledBuffer(this, data);
  }

  void release(byte[] data) {
    if (data == null || data.length != bufferSize) {
      return;
    }
    lock.lock();
    try {
      if (pool.size() < maxPoolSize) {
        pool.addFirst(data);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Encapsulates a borrowed buffer and returns it to the pool when closed.
   */
  public static final class PooledBuffer implements AutoCloseable {
    private final BufferPool owner;
    private byte[] data;
    private boolean released;

    private PooledBuffer(BufferPool owner, byte[] data) {
      this.owner = owner;
      this.data = data;
    }

    /**
     * Exposes the currently borrowed buffer; callers must not retain it after closing.
     *
     * @return active backing array
     */
    public byte[] array() {
      if (released) {
        throw new IllegalStateException("buffer already released");
      }
      return data.clone();
    }

    /**
     * Provides the mutable backing array for callers that need zero-copy access.
     *
     * @return writable backing array (do not retain after close)
     */
    public byte[] borrowWritableArray() {
      if (released) {
        throw new IllegalStateException("buffer already released");
      }
      return data;
    }

    @Override
    public void close() {
      if (released) {
        return;
      }
      owner.release(data);
      released = true;
      data = null;
    }
  }
}
