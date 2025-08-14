package sniffer.domain;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Streaming segment writer:
 *  - begin(meta, headers) -> records header offsets, writes headers
 *  - append(handle, bytes...) -> streams body bytes directly to blob
 *  - end(handle, tsLast) -> writes NDJSON index line with measured body_len
 *
 * Rotation never occurs while any message is in-flight, ensuring headers and body
 * are always in the same blob file.
 */
public final class SegmentSink implements AutoCloseable, HttpEventSink, HttpStreamSink {

  // ===== Public config =====
  public static final class Config {
    public final Path root;
    public final long rotateBytes;
    public final long rotateSeconds;
    public final int queueCapacity;
    public final int batch;
    public final boolean gzipIndex;
    public Config(Path root, long rotateBytes, long rotateSeconds,
                  int queueCapacity, int batch, boolean gzipIndex){
      this.root=root; this.rotateBytes=rotateBytes; this.rotateSeconds=rotateSeconds;
      this.queueCapacity=queueCapacity; this.batch=batch; this.gzipIndex=gzipIndex;
    }
    public static Config hourlyGiB(Path root){ return new Config(root, 1L<<30, 3600, 32768, 512, false); }
  }

  // ===== Streaming sink API =====
  public static final class Meta {
    public final String id, kind, session, src, dst;
    public final long tsFirst;
    long tsLast; // will be updated at end
    String firstLine; // filled from headers on OPEN
    Meta(String id, String kind, String session, String src, String dst, long tsFirst, long tsLast){
      this.id=id; this.kind=kind; this.session=session; this.src=src; this.dst=dst; this.tsFirst=tsFirst; this.tsLast=tsLast;
    }
  }
  public static final class StreamHandle implements HttpStream {
    final long sid;
    StreamHandle(long sid){ this.sid=sid; }
  }

  // ===== Internals =====
  private final Config cfg;
  private final BlockingQueue<Cmd> q;
  private final Thread worker;
  private final RollingFiles files;

  private long nextSid = 1;

  public SegmentSink(Config cfg) throws IOException {
    this.cfg = cfg;
    Files.createDirectories(cfg.root);
    this.files = new RollingFiles(cfg.root, cfg.rotateBytes, cfg.rotateSeconds, cfg.gzipIndex);
    this.q = new ArrayBlockingQueue<>(cfg.queueCapacity);
    this.worker = new Thread(this::drainLoop, "segment-writer");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  // ===== HttpEventSink (compat; not used in streaming path) =====
  @Override public boolean offer(HttpMessage m) {
    // Fall back to streaming path internally (OPEN -> APPEND -> CLOSE).
    Meta meta = new Meta(m.id(), m.kind(), m.sessionId(), m.srcIp()+":"+m.srcPort(), m.dstIp()+":"+m.dstPort(), m.tsFirstMicros(), m.tsLastMicros());
    StreamHandle h = begin(meta, m.rawStartLineAndHeaders());
    byte[] body = m.rawBody();
    if (body != null && body.length > 0) append(h, body, 0, body.length);
    end(h, m.tsLastMicros());
    return true;
  }

  // ===== HttpStreamSink =====
  @Override public StreamHandle begin(Meta meta, byte[] headers) {
    long sid = nextSid++;
    put(new OpenCmd(sid, meta, headers));
    return new StreamHandle(sid);
  }
  @Override public void append(HttpStream handle, byte[] data, int off, int len) {
    if (len <= 0) return;
    put(new AppendCmd(((StreamHandle)handle).sid, Arrays.copyOfRange(data, off, off+len)));
  }
  @Override public void end(HttpStream handle, long tsLast) {
    put(new CloseCmd(((StreamHandle)handle).sid, tsLast));
  }

  private void put(Cmd c){
    try { q.put(c); } catch (InterruptedException ie){ Thread.currentThread().interrupt(); }
  }

  // ===== Worker =====
  private void drainLoop(){
    ArrayList<Cmd> batch = new ArrayList<>(cfg.batch);
    try {
      while (!Thread.currentThread().isInterrupted()){
        Cmd first = q.take(); batch.add(first); q.drainTo(batch, cfg.batch-1);
        for (Cmd c : batch) c.apply(files);
        files.maybeFlush();
        batch.clear();
      }
    } catch (InterruptedException ie){ Thread.currentThread().interrupt(); }
    catch (Throwable t){ t.printStackTrace(); }
    finally { try { files.close(); } catch (IOException ignore) {} }
  }

  @Override public void close(){
    worker.interrupt();
  }

  // ===== Commands =====
  private sealed interface Cmd permits OpenCmd, AppendCmd, CloseCmd {
    void apply(RollingFiles f) throws IOException;
  }
  private static final class OpenCmd implements Cmd {
    final long sid; final Meta meta; final byte[] headers;
    OpenCmd(long sid, Meta meta, byte[] headers){ this.sid=sid; this.meta=meta; this.headers=headers; }
    @Override public void apply(RollingFiles f) throws IOException { f.openStream(sid, meta, headers); }
  }
  private static final class AppendCmd implements Cmd {
    final long sid; final byte[] data;
    AppendCmd(long sid, byte[] data){ this.sid=sid; this.data=data; }
    @Override public void apply(RollingFiles f) throws IOException { f.appendBody(sid, data); }
  }
  private static final class CloseCmd implements Cmd {
    final long sid; final long tsLast;
    CloseCmd(long sid, long tsLast){ this.sid=sid; this.tsLast=tsLast; }
    @Override public void apply(RollingFiles f) throws IOException { f.closeStream(sid, tsLast); }
  }

  // ===== Rolling files with per-stream state =====
  static final class RollingFiles implements Closeable {
    private final Path root; private final long maxBytes; private final long maxAgeSec; private final boolean gzipIndex;
    private FileChannel blob; private OutputStream index;
    private long blobSize=0; private long openedAtSec=0;
    private Path blobPath, indexPath;
    private long sinceFlush=0;
    private int activeStreams=0;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final class InFlight {
      Meta meta;
      long headersOff, headersLen;
      long bodyOff, bodyLen;
      InFlight(Meta m){ this.meta=m; }
    }
    private final Map<Long, InFlight> inflight = new HashMap<>();

    RollingFiles(Path root, long maxBytes, long maxAgeSec, boolean gzipIndex) throws IOException {
      this.root=root; this.maxBytes=maxBytes; this.maxAgeSec=maxAgeSec; this.gzipIndex=gzipIndex; roll();
    }

    synchronized void openStream(long sid, Meta meta, byte[] headers) throws IOException {
      rollIfNeeded();
      activeStreams++;
      InFlight st = new InFlight(meta);
      st.headersOff = blob.position();
      writeFully(blob, ByteBuffer.wrap(headers));
      st.headersLen = blob.position() - st.headersOff;
      st.bodyOff = blob.position();
      meta.firstLine = firstLineAscii(headers);
      inflight.put(sid, st);
      blobSize = blob.position();
    }

    synchronized void appendBody(long sid, byte[] data) throws IOException {
      InFlight st = inflight.get(sid);
      if (st == null) return; // stream ended or unknown; ignore quietly
      writeFully(blob, ByteBuffer.wrap(data));
      st.bodyLen += data.length;
      blobSize = blob.position();
    }

    synchronized void closeStream(long sid, long tsLast) throws IOException {
      InFlight st = inflight.remove(sid);
      if (st == null) return;
      st.meta.tsLast = tsLast;

      String blobName = blobPath.getFileName().toString();
      String json = toJsonLine(st.meta, blobName, st.headersOff, st.headersLen, st.bodyOff, st.bodyLen);
      index.write(json.getBytes(StandardCharsets.UTF_8));
      index.write('\n');
      sinceFlush++;
      activeStreams--;
    }

    synchronized void maybeFlush() throws IOException {
      if (sinceFlush >= 2048){
        index.flush();
        blob.force(false);
        sinceFlush = 0;
      }
    }

    private String toJsonLine(Meta m, String blobName,
                              long offH, long lenH, long offB, long lenB){
      String sid = (m.session==null? "null" : "\""+escape(m.session)+"\"");
      return new StringBuilder(256 + (m.firstLine==null?0:m.firstLine.length()))
        .append("{\"ts_first\":").append(m.tsFirst)
        .append(",\"ts_last\":").append(m.tsLast)
        .append(",\"id\":\"").append(m.id).append("\"")
        .append(",\"kind\":\"").append(m.kind).append("\"")
        .append(",\"session\":").append(sid)
        .append(",\"src\":\"").append(m.src).append('"')
        .append(",\"dst\":\"").append(m.dst).append('"')
        .append(",\"first_line\":\"").append(escape(m.firstLine==null?"":m.firstLine)).append('"')
        .append(",\"headers_blob\":\"").append(blobName).append('"')
        .append(",\"headers_off\":").append(offH).append(",\"headers_len\":").append(lenH)
        .append(",\"body_blob\":\"").append(blobName).append('"')
        .append(",\"body_off\":").append(offB).append(",\"body_len\":").append(lenB)
        .append('}')
        .toString();
    }

    private static String escape(String s){
      StringBuilder b=new StringBuilder((int)(s.length()*1.1)+4);
      for (int i=0;i<s.length();i++){
        char c=s.charAt(i);
        if (c=='"'||c=='\\') b.append('\\').append(c);
        else if (c=='\n') b.append("\\n");
        else if (c=='\r') b.append("\\r");
        else if (c=='\t') b.append("\\t");
        else if (c<0x20) b.append(' ');
        else b.append(c);
      }
      return b.toString();
    }

    private static String firstLineAscii(byte[] headers){
      int limit = Math.min(headers.length, 4096);
      for (int i=0;i+1<limit;i++){
        if (headers[i]==13 && headers[i+1]==10){
          int len = Math.min(i, 1024);
          return new String(headers, 0, len, StandardCharsets.ISO_8859_1).trim();
        }
      }
      return "";
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
      while (buf.hasRemaining()) ch.write(buf);
    }

    private void rollIfNeeded() throws IOException {
      long now = Instant.now().getEpochSecond();
      if (activeStreams > 0) return; // do not roll mid-message
      if (blobSize >= maxBytes || (now - openedAtSec) >= maxAgeSec){
        roll();
      }
    }

    private void roll() throws IOException {
      closeCurrent();
      String stamp = TS.format(Instant.now());
      blobPath = root.resolve("blob-"+stamp+".seg");
      indexPath = root.resolve("index-"+stamp+".ndjson");
      blob = FileChannel.open(blobPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
      OutputStream base = Files.newOutputStream(indexPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      index = base; // could GZIP if desired
      blobSize = blob.position(); openedAtSec = Instant.now().getEpochSecond(); sinceFlush=0;
    }

    private void closeCurrent() throws IOException {
      if (index!=null){ index.flush(); index.close(); index=null; }
      if (blob!=null){ blob.force(true); blob.close(); blob=null; }
    }

    @Override public void close() throws IOException { closeCurrent(); }
  }
}
