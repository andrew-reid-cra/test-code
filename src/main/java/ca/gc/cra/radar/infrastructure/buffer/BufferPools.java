package ca.gc.cra.radar.infrastructure.buffer;

/**
 * Central registry for shared {@link BufferPool} instances used by IO components.
 */
public final class BufferPools {
  private static final int IO_BUFFER_BYTES = 64 * 1024;
  private static final int MAX_POOL_ENTRIES = Math.max(4, Runtime.getRuntime().availableProcessors());

  private static final BufferPool IO_POOL = new BufferPool(IO_BUFFER_BYTES, MAX_POOL_ENTRIES);

  private BufferPools() {}

  /**
   * Provides the shared IO buffer pool sized for file/network writes.
   */
  public static BufferPool ioBuffers() {
    return IO_POOL;
  }
}
