// sniffer/domain/MessageFileSink.java
package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.*;

public final class MessageFileSink implements AutoCloseable {
  public static final class Config {
    public final Path root;
    public final boolean gzip;
    public final int queueCapacity;
    public Config(Path root, boolean gzip, int queueCapacity){
      this.root = root; this.gzip = gzip; this.queueCapacity = queueCapacity;
    }
  }
  private final BlockingQueue<HttpMessage> q;
  private final Thread worker;
  private final Config cfg;

  public MessageFileSink(Config cfg) throws IOException {
    this.cfg = cfg;
    Files.createDirectories(cfg.root.resolve("requests"));
    Files.createDirectories(cfg.root.resolve("responses"));
    this.q = new ArrayBlockingQueue<>(cfg.queueCapacity);
    this.worker = new Thread(this::drainLoop, "msg-writer");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  public boolean offer(HttpMessage m){ return q.offer(m); }

  private void drainLoop(){
    List<HttpMessage> batch = new ArrayList<>(256);
    try {
      while (!Thread.currentThread().isInterrupted()){
        HttpMessage m = q.take();
        batch.add(m);
        q.drainTo(batch, 255);
        for (HttpMessage x: batch) writeOne(x);
        batch.clear();
      }
    } catch (InterruptedException ie){
      Thread.currentThread().interrupt();
    } catch (Throwable t){
      t.printStackTrace();
    }
  }

  private void writeOne(HttpMessage m) {
    boolean req = m instanceof HttpRequest;
    String id = m.id();
    String shard = id.substring(0, Math.min(2, id.length()));
    Path dir = cfg.root.resolve(req ? "requests" : "responses").resolve(shard);
    try { Files.createDirectories(dir); } catch (IOException ignored) {}
    String name = String.format("%s-%s-%s_%d-%s_%d.http",
        id, req ? "REQ" : "RSP", m.srcIp(), m.srcPort(), m.dstIp(), m.dstPort());
    Path tmp = dir.resolve(name + ".tmp");
    Path fin = dir.resolve(name);

    try (OutputStream os = Files.newOutputStream(tmp,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
         BufferedOutputStream bos = new BufferedOutputStream(os, 1<<16)) {
      String preface = "# ts_first: %d%n# ts_last:  %d%n# id:       %s%n# session:  %s%n# flow:     %s:%d -> %s:%d%n%n"
          .formatted(m.tsFirstMicros(), m.tsLastMicros(), id,
              m.sessionId()==null?"":m.sessionId(),
              m.srcIp(), m.srcPort(), m.dstIp(), m.dstPort());
      bos.write(preface.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      bos.write(m.rawStartLineAndHeaders());
      byte[] b = m.rawBody();
      if (b!=null && b.length>0) bos.write(b);
      bos.flush();
      Files.move(tmp, fin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ioe){
      // best-effort: drop file & continue
      try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
    }
  }

  @Override public void close(){
    worker.interrupt();
  }
}



