package sniffer.poster;

import java.io.*;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.Inflater;

/** Streaming utilities: copy, dechunk, decompress. */
final class Streams {
  private Streams(){}

  static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[1<<16];
    for (int r; (r=in.read(buf)) >= 0; ) out.write(buf, 0, r);
  }

  static InputStream gzip(InputStream in) throws IOException {
    return new GZIPInputStream(in, 1<<16);
  }
  static InputStream deflate(InputStream in) {
    // 'nowrap=true' handles raw deflate without zlib header if servers misbehave
    return new InflaterInputStream(in, new Inflater(true), 1<<16);
  }
  static InputStream brotliIfPresent(InputStream in) {
    try {
      // Optional: org.brotli.dec.BrotliInputStream (if on classpath)
      Class<?> k = Class.forName("org.brotli.dec.BrotliInputStream");
      return (InputStream)k.getConstructor(InputStream.class).newInstance(in);
    } catch (Throwable ignore) {
      return in; // fall through without error
    }
  }

  /** RFC 7230 chunked transfer decoder as an InputStream wrapper. */
  static final class DechunkingInputStream extends InputStream {
    private final InputStream in;
    private long chunkRemain = -1;
    private boolean inTrailers = false;
    private boolean eof = false;

    DechunkingInputStream(InputStream in){ this.in = in; }

    @Override public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return (n <= 0 ? -1 : (one[0] & 0xff));
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (eof) return -1;
      if (inTrailers) return readTrailers(b, off, len);

      while (chunkRemain <= 0) {
        // read chunk-size line
        String line = readLine();
        if (line == null) return -1;
        String s = line.trim();
        int semi = s.indexOf(';');
        String hex = (semi >= 0 ? s.substring(0, semi) : s).trim();
        if (hex.isEmpty()) continue;
        long size = 0;
        for (int i=0;i<hex.length();i++){
          int d = Character.digit(hex.charAt(i), 16);
          if (d < 0) { size = 0; break; }
          size = (size << 4) + d;
        }
        chunkRemain = size;
        if (size == 0) { inTrailers = true; break; }
      }
      if (inTrailers) return readTrailers(b, off, len);

      int want = (int)Math.min(len, chunkRemain);
      int n = in.read(b, off, want);
      if (n < 0) return -1;
      chunkRemain -= n;
      if (chunkRemain == 0){
        // consume CRLF
        int c1 = in.read(); int c2 = in.read();
        if (c1 != '\r' || c2 != '\n') { /* tolerate */ }
        chunkRemain = -1;
      }
      return n;
    }

    private int readTrailers(byte[] b, int off, int len) throws IOException {
      // Read until CRLF CRLF, but we just consume and discard trailers for poster output.
      // To keep headers visible, we could forward them; for compactness we drop them.
      // Consume lines until blank line.
      String line;
      while ((line = readLine()) != null) {
        if (line.isEmpty()) { eof = true; return -1; }
      }
      eof = true;
      return -1;
    }

    private String readLine() throws IOException {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
      int c, prev = -1;
      while ((c = in.read()) >= 0) {
        if (prev == '\r' && c == '\n') break;
        if (prev >= 0) baos.write(prev);
        prev = c;
      }
      if (c < 0 && prev < 0) return null;
      if (prev >= 0 && !(prev == '\r')) baos.write(prev);
      return baos.toString("ISO-8859-1");
    }
  }
}
