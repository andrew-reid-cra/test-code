package ca.gc.cra.radar.infrastructure.buffer;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Expandable byte buffer backed by a single array with manual read/write indices.
 * <p>Supports amortized O(1) append operations with exponential growth and exposes the
 * readable region as either the backing array or a read-only {@link ByteBuffer} view.
 */
public final class GrowableBuffer {
  private static final int DEFAULT_CAPACITY = 2048;
  private static final int MAX_CAPACITY = 32 * 1024 * 1024; // 32 MiB safety guard

  private byte[] data;
  private int readIndex;
  private int writeIndex;

  /**
   * Creates a buffer using the default initial capacity.
   */
  public GrowableBuffer() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates a buffer with a caller-supplied initial capacity.
   *
   * @param initialCapacity minimum backing array size
   * @throws IllegalArgumentException when {@code initialCapacity} is not positive
   */
  public GrowableBuffer(int initialCapacity) {
    if (initialCapacity <= 0) {
      throw new IllegalArgumentException("initialCapacity must be positive");
    }
    data = new byte[Math.min(MAX_CAPACITY, align(initialCapacity))];
    readIndex = 0;
    writeIndex = 0;
  }

  /**
   * Appends a single byte into the buffer.
   */
  public void writeByte(byte value) {
    ensureWritable(1);
    data[writeIndex++] = value;
  }

  /**
   * Appends the provided bytes into the buffer, growing it if required.
   *
   * @param src source array; must not be {@code null}
   */
  public void write(byte[] src) {
    Objects.requireNonNull(src, "src");
    write(src, 0, src.length);
  }

  /**
   * Appends a region of the provided array into the buffer, growing it if required.
   *
   * @param src source array; must not be {@code null}
   * @param offset starting offset within {@code src}
   * @param length number of bytes to append
   */
  public void write(byte[] src, int offset, int length) {
    Objects.requireNonNull(src, "src");
    if (length <= 0) {
      return;
    }
    if (offset < 0 || offset + length > src.length) {
      throw new IndexOutOfBoundsException("invalid offset/length");
    }
    ensureWritable(length);
    System.arraycopy(src, offset, data, writeIndex, length);
    writeIndex += length;
  }

  /**
   * Returns the number of readable bytes.
   */
  public int readableBytes() {
    return writeIndex - readIndex;
  }

  /**
   * Provides the backing array for zero-copy inspection.
   */
  public byte[] array() {
    return data;
  }

  /**
   * Returns the current reader index.
   */
  public int readerIndex() {
    return readIndex;
  }

  /**
   * Advances the reader index by {@code length} bytes.
   */
  public void discard(int length) {
    if (length < 0 || length > readableBytes()) {
      throw new IllegalArgumentException("length out of bounds: " + length);
    }
    readIndex += length;
    if (readIndex == writeIndex) {
      // reset to avoid pathological growth when alternating read/write
      readIndex = 0;
      writeIndex = 0;
    }
  }

  /**
   * Copies {@code length} readable bytes into a freshly allocated array.
   */
  public byte[] copy(int length) {
    if (length < 0 || length > readableBytes()) {
      throw new IllegalArgumentException("length out of bounds: " + length);
    }
    byte[] out = new byte[length];
    System.arraycopy(data, readIndex, out, 0, length);
    discard(length);
    return out;
  }

  /**
   * Copies all readable bytes into a freshly allocated array.
   */
  public byte[] copyAll() {
    return copy(readableBytes());
  }

  /**
   * Copies {@code length} readable bytes into the provided destination array.
   */
  public void copyInto(byte[] dest, int destOffset, int length) {
    Objects.requireNonNull(dest, "dest");
    if (destOffset < 0 || destOffset + length > dest.length) {
      throw new IndexOutOfBoundsException("invalid dest offset/length");
    }
    if (length < 0 || length > readableBytes()) {
      throw new IllegalArgumentException("length out of bounds: " + length);
    }
    System.arraycopy(data, readIndex, dest, destOffset, length);
    discard(length);
  }

  /**
   * Finds the first occurrence of {@code needle} starting at the reader index.
   *
   * @return relative index or {@code -1} when not found
   */
  public int indexOf(byte[] needle) {
    Objects.requireNonNull(needle, "needle");
    int readable = readableBytes();
    int needleLen = needle.length;
    if (needleLen == 0) {
      return 0;
    }
    if (needleLen > readable) {
      return -1;
    }
    int limit = writeIndex - needleLen;
    outer:
    for (int i = readIndex; i <= limit; i++) {
      for (int j = 0; j < needleLen; j++) {
        if (data[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i - readIndex;
    }
    return -1;
  }

  /**
   * Exposes the readable region as a read-only {@link ByteBuffer} view.
   */
  public ByteBuffer readableBuffer() {
    return ByteBuffer.wrap(data, readIndex, readableBytes()).asReadOnlyBuffer();
  }

  /**
   * Ensures the buffer can accommodate {@code requiredReadable} bytes without additional reallocations.
   */
  public void ensureCapacity(int requiredReadable) {
    int readable = readableBytes();
    if (requiredReadable <= readable) {
      return;
    }
    int neededWritable = requiredReadable - readable;
    ensureWritable(neededWritable);
  }

  /**
   * Ensures at least {@code minWritableBytes} bytes can be appended without reallocating.
   */
  public void ensureWritable(int minWritableBytes) {
    if (minWritableBytes <= 0) {
      return;
    }
    int writable = data.length - writeIndex;
    if (writable >= minWritableBytes) {
      return;
    }
    compact();
    writable = data.length - writeIndex;
    if (writable >= minWritableBytes) {
      return;
    }
    int required = readableBytes() + minWritableBytes;
    int newCapacity = data.length;
    while (newCapacity < required && newCapacity < MAX_CAPACITY) {
      newCapacity <<= 1;
    }
    if (newCapacity < required) {
      newCapacity = required;
    }
    if (newCapacity > MAX_CAPACITY) {
      throw new IllegalStateException("buffer would exceed max capacity: " + newCapacity);
    }
    byte[] next = new byte[newCapacity];
    int readable = readableBytes();
    System.arraycopy(data, readIndex, next, 0, readable);
    data = next;
    readIndex = 0;
    writeIndex = readable;
  }

  /**
   * Clears the buffer content without shrinking its capacity.
   */
  public void clear() {
    readIndex = 0;
    writeIndex = 0;
  }

  private void compact() {
    if (readIndex == 0) {
      return;
    }
    int readable = readableBytes();
    if (readable > 0) {
      System.arraycopy(data, readIndex, data, 0, readable);
    }
    readIndex = 0;
    writeIndex = readable;
  }

  private static int align(int value) {
    int n = 1;
    while (n < value) {
      n <<= 1;
    }
    return n;
  }
}
