package sniffer.poster;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Poster: read Assembler's index lines and blob segments, pair REQ/RSP by TID,
 * stream-decode (chunked + gzip/deflate; brotli if available), and write
 * per-transaction human-readable .http files.
 *
 * Usage:
 *   java -Xms1g -Xmx8g sniffer.poster.PosterMain \
 *     --in /disk/http-out --out /disk/poster-out \
 *     --workers 32 --decode all --maxOpenBlobs 256 [--indexGlob 'index-*.ndjson']
 */
public final class PosterMain {

  // ---------- CLI defaults ----------
  private static final int    DEF_WORKERS       = Math.max(2, Runtime.getRuntime().availableProcessors());
  private static final String DEF_DECODE        = "all";  // none | transfer | all
  private static final int    DEF_MAX_OPENBLOBS = 256;
  private static final int    DEF_MAX_INFLIGHT  = 2_000_000;
  private static final long   PROGRESS_EVERY    = 250_000;

  // ---------- Data model ----------
  static final class Part {
    final String tid, dir;
    final String hdrBlob, bodyBlob;
    final long hdrOff, hdrLen, bodyOff, bodyLen;
    final String method, host, path, status;
    final String xferEnc, contentEnc, contentType;
    final String a, b; final int ap, bp;
    final long tsFirst, tsLast;
    final String session;

    Part(String tid, String dir,
         String hdrBlob, String bodyBlob,
         long hdrOff, long hdrLen, long bodyOff, long bodyLen,
         String method, String host, String path, String status,
         String xferEnc, String contentEnc, String contentType,
         String a, int ap, String b, int bp, long tsFirst, long tsLast, String session) {
      this.tid=tid; this.dir=dir;
      this.hdrBlob=hdrBlob; this.bodyBlob=bodyBlob;
      this.hdrOff=hdrOff; this.hdrLen=hdrLen; this.bodyOff=bodyOff; this.bodyLen=bodyLen;
      this.method=method; this.host=host; this.path=path; this.status=status;
      this.xferEnc = xferEnc; this.contentEnc = contentEnc; this.contentType = contentType;
      this.a=a; this.ap=ap; this.b=b; this.bp=bp; this.tsFirst=tsFirst; this.tsLast=tsLast; this.session=session;
    }
  }
  static final class Pair {
    volatile Part req; volatile Part rsp;
    void add(Part p){ if ("REQ".equals(p.dir)) req=p; else rsp=p; }
  }

  // ---------- Main ----------
  public static void main(String[] args) throws Exception {
    Map<String,String> cli = parseArgs(args);
    Path inDir  = Paths.get(req(cli, "--in"));
    Path outDir = Paths.get(req(cli, "--out"));
    int  workers = intOpt(cli, "--workers", DEF_WORKERS);
    String decode = cli.getOrDefault("--decode", DEF_DECODE); // none|transfer|all
    int  maxOpen = intOpt(cli, "--maxOpenBlobs", DEF_MAX_OPENBLOBS);
    int  maxInflight = intOpt(cli, "--maxInflightTids", DEF_MAX_INFLIGHT);
    String indexGlob = cli.getOrDefault("--indexGlob", "<auto>");

    Files.createDirectories(outDir);

    System.out.printf("%s POST Config in=%s out=%s workers=%d decode=%s maxOpenBlobs=%d indexGlob=%s%n",
        Util.tsNow(), inDir, outDir, workers, decode, maxOpen, indexGlob);

    // Pre-scan blobs for fallback logic
    List<Path> allBlobs = listBlobFiles(inDir);
    final String singleBlobName =
        (allBlobs.size() == 1 ? allBlobs.get(0).getFileName().toString() : null);

    try (BlobStore blobs = new BlobStore(inDir, maxOpen)) {
      ExecutorService pool = new ThreadPoolExecutor(
          workers, workers, 30, TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(),
          r -> { Thread t = new Thread(r, "poster-worker"); t.setDaemon(true); return t; });

      ConcurrentHashMap<String, Pair> inflight = new ConcurrentHashMap<>(1<<20);
      AtomicLong linesSeen = new AtomicLong();
      AtomicLong pairsEmitted = new AtomicLong();

      List<Path> indexFiles = listIndexFiles(inDir, indexGlob);
      System.out.printf("%s POST Found %d index files%n", Util.tsNow(), indexFiles.size());

      for (Path idx : indexFiles) {
        final String siblingBlob = deriveSiblingBlobName(inDir, idx, allBlobs, singleBlobName);
        long nLocal = 0;

        try (BufferedReader br = Files.newBufferedReader(idx, StandardCharsets.UTF_8)) {
          for (String line; (line = br.readLine()) != null; ) {
            nLocal++; long n = linesSeen.incrementAndGet();
            Part p = parseIndexLine(line);

            if (p == null) continue;

            // Fill in blob names if missing
            String hb = p.hdrBlob, bb = p.bodyBlob;
            if ((hb == null || hb.isEmpty()) && (bb == null || bb.isEmpty())) {
              String use = siblingBlob != null ? siblingBlob : singleBlobName;
              if (use == null) {
                if ((nLocal & 1023) == 0) {
                  System.err.printf("%s POST WARNING: missing blobs and no default; line %,d in %s%n",
                      Util.tsNow(), nLocal, idx.getFileName());
                }
                continue;
              }
              p = withBlobs(p, use, use);
            } else {
              if (hb == null || hb.isEmpty()) hb = (bb != null ? bb : (siblingBlob != null ? siblingBlob : singleBlobName));
              if (bb == null || bb.isEmpty()) bb = (hb != null ? hb : (siblingBlob != null ? siblingBlob : singleBlobName));
              p = withBlobs(p, hb, bb);
            }

            // backpressure
            while (inflight.size() > maxInflight) Thread.sleep(2);

            Pair slot = inflight.computeIfAbsent(p.tid, k -> new Pair());
            slot.add(p);
            if (slot.req != null && slot.rsp != null) {
              inflight.remove(p.tid, slot);
              pairsEmitted.incrementAndGet();
              Part reqP = slot.req, rspP = slot.rsp;
              pool.execute(() -> writeTransaction(blobs, outDir, reqP, rspP, decode));
            }

            if ((n % PROGRESS_EVERY) == 0) {
              System.out.printf("%s POST Read %,d lines; inflight %,d; written pairs %,d%n",
                  Util.tsNow(), n, inflight.size(), pairsEmitted.get());
            }
          }
        }
        System.out.printf("%s POST Scanned %s (%,d lines)%n", Util.tsNow(), idx.getFileName(), nLocal);
      }

      // flush leftovers
      System.out.printf("%s POST Flushing %,d incomplete TIDs...%n", Util.tsNow(), inflight.size());
      for (Map.Entry<String,Pair> e : inflight.entrySet()) {
        Pair pr = e.getValue();
        Part req = pr.req, rsp = pr.rsp;
        pool.execute(() -> writeTransaction(blobs, outDir, req, rsp, decode));
      }

      pool.shutdown();
      pool.awaitTermination(365, TimeUnit.DAYS);
      System.out.printf("%s POST Done. Pairs written: %,d  singles: %,d%n",
          Util.tsNow(), pairsEmitted.get(), Math.max(0, inflight.size()));
    }
  }

  private static Part withBlobs(Part p, String hdrBlob, String bodyBlob){
    return new Part(p.tid, p.dir,
        hdrBlob, bodyBlob,
        p.hdrOff, p.hdrLen, p.bodyOff, p.bodyLen,
        p.method, p.host, p.path, p.status, p.xferEnc, p.contentEnc, p.contentType,
        p.a, p.ap, p.b, p.bp, p.tsFirst, p.tsLast, p.session);
  }

  // ---------- Transaction writer ----------
  private static void writeTransaction(BlobStore blobs, Path outDir, Part req, Part rsp, String decodeMode){
    Part anchor = (req != null ? req : rsp);
    String safeHost = safe(anchor.host);
    String ses = anchor.session == null ? "-" : safe(anchor.session);
    String name = String.format("%d-%s-%s-%s-%s-%s.http",
        anchor.tsFirst, anchor.tid, ses, safeHost,
        (req!=null? safeOr(req.method,"REQ"):"REQ"),
        (rsp!=null? safeOr(rsp.status,"RSP"):"RSP"));
    Path out = outDir.resolve(name);

    try (OutputStream fos = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
         BufferedOutputStream bos = new BufferedOutputStream(fos, 1<<20)) {

      writeLine(bos, String.format("# TID=%s  SESSION=%s", anchor.tid, safeOr(anchor.session,"-")));
      if (req != null) writeLine(bos, String.format("# REQ %s  tsFirst=%d tsLast=%d  %s %s",
          addr(req.a, req.ap) + " -> " + addr(req.b, req.bp), req.tsFirst, req.tsLast, safeOr(req.method,"-"), safeOr(req.path,"-")));
      if (rsp != null) writeLine(bos, String.format("# RSP %s  tsFirst=%d tsLast=%d  status=%s",
          addr(rsp.a, rsp.ap) + " -> " + addr(rsp.b, rsp.bp), rsp.tsFirst, rsp.tsLast, safeOr(rsp.status,"-")));
      writeLine(bos, "");

      if (req != null) {
        writeBlock("-- REQUEST HEADERS --", bos);
        if (req.hdrOff >= 0 && req.hdrLen > 0) blobs.copyBytes(req.hdrBlob, req.hdrOff, req.hdrLen, bos);
        else writeLine(bos, "(no headers: invalid offset/length)");
        writeLine(bos, "");
        writeBlock("-- REQUEST BODY (decoded="+decodeMode+") --", bos);
        streamBody(blobs, req, decodeMode, bos);
        writeLine(bos, "");
      }

      if (rsp != null) {
        writeBlock("-- RESPONSE HEADERS --", bos);
        if (rsp.hdrOff >= 0 && rsp.hdrLen > 0) blobs.copyBytes(rsp.hdrBlob, rsp.hdrOff, rsp.hdrLen, bos);
        else writeLine(bos, "(no headers: invalid offset/length)");
        writeLine(bos, "");
        writeBlock("-- RESPONSE BODY (decoded="+decodeMode+") --", bos);
        streamBody(blobs, rsp, decodeMode, bos);
        writeLine(bos, "");
      }

      bos.flush();
    } catch (Throwable t){
      System.err.printf("%s POST ERROR writing %s : %s%n", Util.tsNow(), out.getFileName(), t);
    }
  }

  private static String addr(String ipPortMaybe, int portField){
    if (ipPortMaybe == null) return "-";
    if (ipPortMaybe.contains(":")) return ipPortMaybe;
    if (portField > 0) return ipPortMaybe + ":" + portField;
    return ipPortMaybe;
  }

  private static void streamBody(BlobStore blobs, Part p, String decodeMode, OutputStream out) throws IOException {
    if (p.bodyLen <= 0 || p.bodyOff < 0 || p.bodyBlob == null) return;
    InputStream raw = blobs.openSlice(p.bodyBlob, p.bodyOff, p.bodyLen);

    boolean doTransfer = "transfer".equalsIgnoreCase(decodeMode) || "all".equalsIgnoreCase(decodeMode);
    boolean doContent  = "all".equalsIgnoreCase(decodeMode);

    InputStream s = raw;
    if (doTransfer && p.xferEnc != null && p.xferEnc.toLowerCase(Locale.ROOT).contains("chunked")) {
      InputStream tryStreams = tryStreamsDechunk(s);
      s = (tryStreams != null) ? tryStreams : new FallbackDechunkInputStream(s);
    }
    if (doContent && p.contentEnc != null) {
      String enc = p.contentEnc.toLowerCase(Locale.ROOT);
      if (enc.contains("gzip")) s = Streams.gzip(s);
      else if (enc.contains("deflate")) s = Streams.deflate(s);
      else if (enc.contains("br")) s = Streams.brotliIfPresent(s); // no-op if Brotli not on classpath
    }
    Streams.copy(s, out);
  }

  private static InputStream tryStreamsDechunk(InputStream s){
    try {
      java.lang.reflect.Method m = Streams.class.getMethod("dechunk", InputStream.class);
      Object o = m.invoke(null, s);
      return (InputStream)o;
    } catch (Throwable ignore){
      return null;
    }
  }

  /** Minimal RFC7230 chunked decoder used only if Streams.dechunk(..) is absent. */
  private static final class FallbackDechunkInputStream extends InputStream {
    private final InputStream in;
    private int chunkRemain = -1;
    private boolean inTrailers = false;
    private boolean closed = false;

    FallbackDechunkInputStream(InputStream in){ this.in = in; }

    @Override public int read() throws IOException {
      byte[] b = new byte[1];
      int n = read(b,0,1);
      return (n<0)? -1 : (b[0]&0xff);
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (closed) return -1;
      while (true){
        if (inTrailers) {
          String line;
          do { line = readLine(in); if (line == null) { closed = true; return -1; } }
          while (!line.isEmpty());
          closed = true; return -1;
        }
        if (chunkRemain < 0){
          String line = readLine(in);
          if (line == null) { closed = true; return -1; }
          int semi = line.indexOf(';');
          String hex = (semi>=0 ? line.substring(0,semi) : line).trim();
          int size = 0;
          for (int i=0;i<hex.length();i++){
            int d = Character.digit(hex.charAt(i),16);
            if (d < 0) break; size = (size<<4) + d;
          }
          chunkRemain = size;
          if (size == 0){ inTrailers = true; }
          continue;
        }
        if (chunkRemain == 0){
          if (consumeCRLF(in)) { chunkRemain = -1; continue; }
          else { closed = true; return -1; }
        }
        int toRead = Math.min(len, chunkRemain);
        int n = in.read(b, off, toRead);
        if (n < 0){ closed = true; return -1; }
        chunkRemain -= n;
        return n;
      }
    }

    private static String readLine(InputStream in) throws IOException {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
      int prev = -1;
      while (true) {
        int c = in.read();
        if (c == -1) return baos.size()==0 ? null : baos.toString(StandardCharsets.ISO_8859_1);
        if (prev == '\r' && c == '\n') {
          byte[] arr = baos.toByteArray();
          int end = arr.length-1; // drop last \r
          return new String(arr, 0, Math.max(0,end), StandardCharsets.ISO_8859_1);
        }
        baos.write(c);
        prev = c;
      }
    }
    private static boolean consumeCRLF(InputStream in) throws IOException {
      int a = in.read(); int b = in.read();
      return a == '\r' && b == '\n';
    }
  }

  // ---------- Index parsing (tolerant) ----------
  private static Part parseIndexLine(String j){
    String tid = anyString(j, "tid", "txid", "id", "stream_id"); if (tid == null) return null;
    String dirRaw = anyString(j, "dir", "kind", "type", "role", "io"); if (dirRaw == null) return null;
    String dir = normalizeDir(dirRaw); if (dir == null) return null;

    // Accept separate blobs for headers/body; also handle single "blob"
    String blobSingle = anyString(j, "blob", "blob_file", "blobfile", "blobName", "seg", "segment", "blobname");
    String hdrBlob    = anyString(j, "headers_blob", "header_blob", "hdr_blob", "hdrBlob");
    String bodyBlob   = anyString(j, "body_blob", "payload_blob", "b_blob", "bodyBlob");
    if (hdrBlob == null) hdrBlob = blobSingle;
    if (bodyBlob == null) bodyBlob = blobSingle;

    // Offsets/lengths: accept both header_* and headers_* (plural), likewise body_*
    long hdrOff = anyLong(j, anyLong(j,-1,"headers_off"), "header_offset", "hdr_off", "hdr_offset", "h_off");
    long hdrLen = anyLong(j, anyLong(j,-1,"headers_len"), "header_len",    "hdr_len", "h_len");
    long bodyOff= anyLong(j, anyLong(j,-1,"body_off"),     "body_offset",   "b_off",   "bodyOff");
    long bodyLen= anyLong(j,  0,                          "body_len",      "b_len",   "len_body", "payload_len");

    String method = anyString(j, "method", "m", "verb");
    String host   = anyString(j, "host", "authority", "server_name");
    String path   = anyString(j, "path", "uri", "url", "request_target");
    String status = anyString(j, "status", "status_code", "code");

    String xferEnc    = anyString(j, "transfer_encoding", "transfer-encoding");
    String contentEnc = anyString(j, "content_encoding", "content-encoding");
    String contentType= anyString(j, "content_type", "content-type");

    String a = anyString(j, "src", "a", "client", "source");
    int ap   = (int) anyLong(j, (int)anyLong(j,0,"ap"), "sport", "src_port", "client_port");
    String b = anyString(j, "dst", "b", "server", "dest");
    int bp   = (int) anyLong(j, (int)anyLong(j,0,"bp"), "dport", "dst_port", "server_port");

    long tsFirst = anyLong(j, anyLong(j,0,"ts_first"), "tsFirst", "ts_first_ms", "t0");
    long tsLast  = anyLong(j, anyLong(j,0,"ts_last"),  "tsLast",  "ts_last_ms",  "t1");

    String session = anyString(j, "session", "sess", "user_session");

    return new Part(tid, dir, hdrBlob, bodyBlob,
        hdrOff, hdrLen, bodyOff, bodyLen,
        method, host, path, status, xferEnc, contentEnc, contentType,
        a, ap, b, bp, tsFirst, tsLast, session);
  }

  private static String normalizeDir(String v){
    String s = v.trim().toUpperCase(Locale.ROOT);
    if (s.equals("REQ") || s.equals("REQUEST") || s.equals("C2S")) return "REQ";
    if (s.equals("RSP") || s.equals("RESPONSE") || s.equals("S2C")) return "RSP";
    return null;
  }

  // ---------- Helpers ----------
  private static String anyString(String j, String... keys){
    for (String k : keys){ String v = Util.getString(j, k); if (v != null) return v; }
    return null;
  }
  private static long anyLong(String j, long def, String... keys){
    for (String k : keys){ long v = Util.getLong(j, k, Long.MIN_VALUE); if (v != Long.MIN_VALUE) return v; }
    return def;
  }

  private static List<Path> listIndexFiles(Path dir, String glob) throws IOException {
    List<Path> out = new ArrayList<>();
    if (!"<auto>".equals(glob)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
        for (Path p : ds) out.add(p);
      }
    } else {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "index-*.ndjson")) {
        for (Path p : ds) out.add(p);
      }
      if (out.isEmpty()) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "index-*.jsonl")) {
        for (Path p : ds) out.add(p);
      }
      if (out.isEmpty()) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.ndjson")) {
        for (Path p : ds) if (p.getFileName().toString().contains("index")) out.add(p);
      }
      if (out.isEmpty()) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jsonl")) {
        for (Path p : ds) if (p.getFileName().toString().contains("index")) out.add(p);
      }
    }
    out.sort(Comparator.comparing(p -> p.getFileName().toString()));
    return out;
  }

  private static List<Path> listBlobFiles(Path dir) throws IOException {
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "blob-*.*")) {
      for (Path p : ds) out.add(p);
    }
    if (out.isEmpty()) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.seg")) {
      for (Path p : ds) out.add(p);
    }
    if (out.isEmpty()) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bin")) {
      for (Path p : ds) out.add(p);
    }
    out.sort(Comparator.comparing(p -> p.getFileName().toString()));
    return out;
  }

  private static String deriveSiblingBlobName(Path dir, Path idx, List<Path> allBlobs, String singleBlobName) throws IOException {
    String idxName = idx.getFileName().toString();
    String token = null;
    if (idxName.startsWith("index-")) {
      int dot = idxName.indexOf('.');
      token = idxName.substring("index-".length(), dot > 0 ? dot : idxName.length());
    }
    if (token != null && !token.isEmpty()) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "blob-" + token + ".*")) {
        for (Path p : ds) return p.getFileName().toString();
      }
      for (Path p : allBlobs) {
        String n = p.getFileName().toString();
        if (n.startsWith("blob-" + token + ".")) return n;
      }
    }
    return singleBlobName;
  }

  private static void writeBlock(String title, OutputStream os) throws IOException {
    writeLine(os, "===== " + title + " =====");
  }
  private static void writeLine(OutputStream os, String s) throws IOException {
    os.write(s.getBytes(StandardCharsets.UTF_8));
    os.write('\n');
  }
  private static String safe(String s){ return s==null? "-" : s.replaceAll("[\\r\\n\\t ]","_"); }
  private static String safeOr(String s, String def){ return s==null? def : safe(s); }

  private static Map<String,String> parseArgs(String[] args){
    Map<String,String> m = new HashMap<>();
    for (int i=0;i<args.length;i++){
      String a = args[i];
      if (a.startsWith("--")){
        String v = "true";
        if (i+1<args.length && !args[i+1].startsWith("--")) v = args[++i];
        m.put(a, v);
      }
    }
    if (!m.containsKey("--in") || !m.containsKey("--out")) {
      System.err.println("Usage: PosterMain --in <http-out-dir> --out <poster-out-dir> [--workers N] [--decode none|transfer|all] [--maxOpenBlobs N] [--maxInflightTids N] [--indexGlob <glob>]");
      System.exit(2);
    }
    return m;
  }
  private static String req(Map<String,String> m, String k){ String v=m.get(k); if (v==null) throw new IllegalArgumentException("Missing "+k); return v; }
  private static int intOpt(Map<String,String> m, String k, int def){ try { return Integer.parseInt(m.getOrDefault(k, String.valueOf(def))); } catch(Exception e){ return def; } }
}


