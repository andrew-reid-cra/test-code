package sniffer.pipe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class SegmentIO {

  private static final byte[] MAGIC = new byte[]{'S','G','B','1'}; // Simple Grab Bag v1
  private static final int    HEADER_LEN = 4;

  // ---- Writer (rotates by MiB) ----
  public static final class Writer implements Closeable, Flushable {
    private final Path dir;
    private final String base;
    private final long rollBytes;
    private CountingOutputStream cos;
    private DataOutputStream out;
    private int part = 0;

    public Writer(Path dir, String base, int rollMiB) throws IOException {
      this.dir = dir;
      this.base = base;
      this.rollBytes = (long) rollMiB * 1024L * 1024L;
      Files.createDirectories(dir);
      openNewFile();
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
        .withZone(ZoneId.systemDefault());

    private void openNewFile() throws IOException {
      close();
      String ts = TS.format(Instant.now());
      String name = String.format("%s-%s-%04d.segbin", base, ts, part++);
      Path p = dir.resolve(name);
      var fos = new FileOutputStream(p.toFile());
      this.cos = new CountingOutputStream(new BufferedOutputStream(fos, 1<<20)); // 1 MiB buffer
      this.out = new DataOutputStream(cos);
      out.write(MAGIC);
      out.flush();
    }

    public void append(SegmentRecord r) throws IOException {
      // record format: [len:int][ts:long][src:utf][sport:int][dst:utf][dport:int][seq:long][flags:int][plen:int][payload...]
      int srcLen = r.src.getBytes(StandardCharsets.UTF_8).length;
      int dstLen = r.dst.getBytes(StandardCharsets.UTF_8).length;
      int recLen = 8 + 2 + srcLen + 4 + 2 + dstLen + 4 + 8 + 4 + 4 + r.len;

      if (cos.getCount() + 4 + recLen > rollBytes) openNewFile();

      out.writeInt(recLen);
      out.writeLong(r.tsMicros);
      out.writeUTF(r.src);
      out.writeInt(r.sport);
      out.writeUTF(r.dst);
      out.writeInt(r.dport);
      out.writeLong(r.seq);
      out.writeInt(r.flags);
      out.writeInt(r.len);
      if (r.len > 0) out.write(r.payload, 0, r.len);
    }

    @Override public void flush() throws IOException { if (out != null) out.flush(); }
    @Override public void close() throws IOException {
      if (out != null) try { out.flush(); } finally { out.close(); out=null; cos=null; }
    }
  }

  // ---- Reader (reads all *.segbin in dir, name-sorted) ----
  public static final class Reader implements Closeable {
    private final List<Path> files;
    private int idx = 0;
    private DataInputStream in;
    private Path current;

    public Reader(Path dir) throws IOException {
      if (!Files.isDirectory(dir)) throw new IOException("Not a directory: " + dir);
      try (var s = Files.list(dir)) {
        files = s.filter(p -> p.getFileName().toString().endsWith(".segbin"))
            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
            .collect(Collectors.toList());
      }
      openNext();
    }

    private void openNext() throws IOException {
      closeStream();
      if (idx >= files.size()) return;
      current = files.get(idx++);
      in = new DataInputStream(new BufferedInputStream(Files.newInputStream(current), 1<<20));
      byte[] hdr = new byte[HEADER_LEN];
      int n = in.read(hdr);
      if (n != HEADER_LEN || !Arrays.equals(hdr, MAGIC)) throw new IOException("Bad header: " + current);
    }

    /** @return next record or null at EOF across all files */
    public SegmentRecord next() throws IOException {
      while (true) {
        if (in == null) return null;
        try {
          int recLen = in.readInt(); // length for skipping if needed
          long ts = in.readLong();
          String src = in.readUTF();
          int sport = in.readInt();
          String dst = in.readUTF();
          int dport = in.readInt();
          long seq = in.readLong();
          int flags = in.readInt();
          int len = in.readInt();
          byte[] payload = new byte[Math.max(0, len)];
          if (len > 0) in.readFully(payload, 0, len);
          return new SegmentRecord().fill(ts, src, sport, dst, dport, seq, flags, payload, len);
        } catch (EOFException eof) {
          openNext();
          continue;
        }
      }
    }

    @Override public void close() throws IOException { closeStream(); }
    private void closeStream() throws IOException { if (in != null) { in.close(); in=null; } }
  }

  // ---- small counting stream ----
  private static final class CountingOutputStream extends FilterOutputStream {
    private long count=0;
    CountingOutputStream(OutputStream out) { super(out); }
    long getCount(){ return count; }
    @Override public void write(int b) throws IOException { out.write(b); count++; }
    @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
  }

  private SegmentIO() {}
}
