package sniffer.poster;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Manages random-access reads into blob-*.bin with an LRU of open channels. */
final class BlobStore implements Closeable {
  private final Path baseDir;
  private final int maxOpen;
  private final LinkedHashMap<String, FileChannel> lru =
      new LinkedHashMap<>(128, 0.75f, true);
  BlobStore(Path baseDir, int maxOpen){ this.baseDir = baseDir; this.maxOpen = Math.max(8, maxOpen); }

  /** Copy exactly len bytes from blob at [off, off+len) to os. */
  void copyBytes(String blobName, long off, long len, OutputStream os) throws IOException {
    if (blobName == null || len <= 0) return;
    try (InputStream in = openSlice(blobName, off, len)) {
      Streams.copy(in, os);
    }
  }

  /** Open a streaming view of (blob, offset, length). The stream ends after len bytes. */
  InputStream openSlice(String blobName, long off, long len) throws IOException {
    if (blobName == null) throw new IllegalArgumentException("blobName");
    FileChannel ch = channel(blobName);
    if (off < 0 || len < 0) throw new IllegalArgumentException("neg off/len");
    return new SliceInputStream(ch, off, len);
  }

  @Override public void close() throws IOException {
    synchronized (lru) {
      for (FileChannel ch : lru.values()) { try { ch.close(); } catch (IOException ignore) {} }
      lru.clear();
    }
  }

  // ---- internals ----
  private FileChannel channel(String blobName) throws IOException {
    synchronized (lru) {
      FileChannel ch = lru.get(blobName);
      if (ch != null && ch.isOpen()) return ch;
      if (ch != null) lru.remove(blobName);

      // trim LRU
      while (lru.size() >= maxOpen) {
        Iterator<Map.Entry<String,FileChannel>> it = lru.entrySet().iterator();
        if (it.hasNext()){
          Map.Entry<String,FileChannel> ev = it.next();
          it.remove();
          try { ev.getValue().close(); } catch (IOException ignore) {}
        }
      }
      Path p = baseDir.resolve(blobName);
      ch = FileChannel.open(p, StandardOpenOption.READ);
      lru.put(blobName, ch);
      return ch;
    }
  }

  /** A bounded InputStream that reads from a FileChannel slice (no heap growth). */
  static final class SliceInputStream extends InputStream {
    private final FileChannel ch;
    private long pos; private final long end;
    private final ByteBuffer buf = ByteBuffer.allocateDirect(1<<16);

    SliceInputStream(FileChannel ch, long offset, long length) {
      this.ch = ch; this.pos = offset; this.end = offset + length;
      buf.limit(0); // empty
    }

    @Override public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return (n <= 0 ? -1 : (one[0] & 0xff));
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (len <= 0) return 0;
      if (pos >= end) return -1;
      if (!buf.hasRemaining()){
        buf.clear();
        long remain = end - pos;
        int want = (int)Math.min(buf.capacity(), remain);
        buf.limit(want);
        int n = ch.read(buf, pos);
        if (n <= 0) return -1;
        buf.flip();
        pos += n;
      }
      int n = Math.min(len, buf.remaining());
      buf.get(b, off, n);
      return n;
    }
  }
}


