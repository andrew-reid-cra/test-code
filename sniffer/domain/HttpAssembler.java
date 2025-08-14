package sniffer.domain;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-throughput TCP reassembly + HTTP/1.x extraction with STREAMING body writes.
 * Focused on low allocation and bounded memory:
 *  - Fixed-capacity staging buffers (no growth)
 *  - Bounded out-of-order (OOO) buffering with global budget
 *  - Tiny hot-path flow cache (L1/L2) to avoid hashmap lookups
 *  - Negative cache for non-HTTP 4-tuples to avoid repeated first-line sniffing
 */
public final class HttpAssembler {

  // -------- Tunables (overridable via -D) --------
  private static final int MAX_CONNS =
      Integer.getInteger("sniffer.maxConns", 20000);
  private static final long IDLE_NS =
      Long.getLong("sniffer.idleNanos", 30_000_000_000L);       // 30s
  private static final int EVICT_EVERY =
      Integer.getInteger("sniffer.evictEvery", 16384);

  // Staging/OOO caps (keep these modest):
  private static final int MAX_HEADER_BYTES =
      Integer.getInteger("sniffer.maxHeaderBytes", 32 * 1024); // 32 KiB
  private static final int BODY_BUF_CAP =
      Integer.getInteger("sniffer.bodyBufCap", 32 * 1024);      // 32 KiB
  private static final int MAX_OOO_BYTES =
      Integer.getInteger("sniffer.maxOooBytes", 256 * 1024);    // per-direction
  private static final long MAX_OOO_GLOBAL =
      Long.getLong("sniffer.maxOooGlobalBytes", 64L << 20);     // 64 MiB for all flows
  private static final long BACKPRESSURE_NS =
      Long.getLong("sniffer.backpressureNanos", 1_000_000L);    // ~1 ms when body staging full

  // Negative (non-HTTP) cache
  private static final int NEG_CACHE_LOG2 =
      Integer.getInteger("sniffer.negCacheLog2", 16);           // 2^16 = 65,536 slots
  private static final long NEG_TTL_NS =
      Long.getLong("sniffer.negTtlNanos", 10_000_000_000L);     // 10s TTL

  // Feature flags (via -Dsniffer.*)
  private static final boolean MIDSTREAM =
      Boolean.getBoolean("sniffer.midstream");          // accept mid-connection headers

  private static final AtomicLong OOO_INUSE = new AtomicLong(0);

  // ---- Constructors ----
  public HttpAssembler(HttpStreamSink streamSink, SessionIdExtractor sessionX){
    this.streamSink = Objects.requireNonNull(streamSink, "streamSink");
    this.sessionX   = Objects.requireNonNull(sessionX, "sessionX");
  }
  public HttpAssembler(MessageFileSink legacySink, SessionIdExtractor sessionX){
    this(defaultStreamSink(java.nio.file.Paths.get("out")), sessionX);
  }
  public HttpAssembler(HttpEventSink legacyEventSink, SessionIdExtractor sessionX){
    this.streamSink = null; this.sessionX = sessionX;
    throw new IllegalArgumentException("Use streaming ctor");
  }

  // ---- Public API: submit one TCP payload segment (already ordered by your reassembler) ----
  public void onTcpSegment(long ts, String src, int sport, String dst, int dport,
                           long seq, boolean fromClient, byte[] p, int off, int len, boolean fin) {

    // --- Hot-path small cache: many segments arrive back-to-back for the same flow ---
    Conn c;
    if (last0.matches(src, sport, dst, dport, fromClient)) {
      c = last0.conn;
    } else if (last1.matches(src, sport, dst, dport, fromClient)) {
      c = last1.conn; last1.swapInto(last0);
    } else {
      // Negative cache check to skip obvious non-HTTP traffic early
      long nkey = negKey(src, sport, dst, dport);
      long now  = System.nanoTime();
      if (negCached(nkey, now)) return;

      // Normalized key for map (client->server orientation)
      FlowKey scratch = SCRATCH.get();
      if (fromClient) scratch.set(src, sport, dst, dport);
      else            scratch.set(dst, dport, src, sport);

      c = flows.get(scratch);
      if (c == null) {
        // Fast sniff before allocating a connection object
        int canLen = Math.min(len, 24);
        boolean looks = looksLikeHttpFirstLine(p, off, canLen, fromClient);
        if (!looks && !MIDSTREAM) { putNeg(nkey, now); return; }
        // Install connection
        FlowKey key = scratch.frozenCopy();
        Conn fresh = new Conn();
        Conn prev  = flows.putIfAbsent(key, fresh);
        c = (prev != null) ? prev : fresh;
      }

      // Update hot cache
      last1.setFrom(src, sport, dst, dport, fromClient, c);
      last1.swapInto(last0);
    }

    c.lastSeenNs = System.nanoTime();
    Dir d = fromClient ? c.c2s : c.s2c;
    if (d.neverHttp) return;

    // Periodic eviction pass
    if ((++evictTick & (EVICT_EVERY - 1)) == 0) sweepEvictions(c.lastSeenNs);

    // --- Reassembly bookkeeping ---
    if (d.nextSeq == -1) { d.nextSeq = seq + len; d.tsFirst = ts; d.tsLast = ts; }
    else if (seq == d.nextSeq) { d.nextSeq += len; d.tsLast = ts; }
    else if (seq > d.nextSeq) {
      // OOO: check budgets BEFORE any copy
      int remainingDir = MAX_OOO_BYTES - d.oooBytes;
      if (remainingDir <= 0) { d.neverHttp = true; resetDir(d); return; }
      int toCopy = Math.min(len, remainingDir);
      if (!reserveOoo(toCopy)) { d.neverHttp = true; resetDir(d); return; }
      byte[] copy = new byte[toCopy];
      System.arraycopy(p, off, copy, 0, toCopy);
      d.oooBytes += toCopy;
      d.ooo.put(seq, copy);
      return;
    } else {
      // old/dup segment
      return;
    }

    // --- Append payload into FIXED buffers ---
    if (!d.candidateChecked) {
      int canLen2 = Math.min(len, 24);
      d.candidateChecked = true;
      boolean looks = looksLikeHttpFirstLine(p, off, canLen2, fromClient);
      if (!looks && !MIDSTREAM) { d.neverHttp = true; return; }
      d.looksHttp = looks || MIDSTREAM;
    }

    if (!d.haveHeaders) {
      int pos = off, rem = len;
      while (rem > 0 && !d.haveHeaders) {
        int wrote = d.headBuf.writeSome(p, pos, rem);
        pos += wrote; rem -= wrote;

        // Drain any in-order OOO into header buf
        drainOooIntoBuf(d, /*toBody*/false);

        int idx = indexOfCrlfCrlf(d.headBuf.buf, 0, d.headBuf.size);
        if (idx >= 0) {
          byte[] headers = Arrays.copyOf(d.headBuf.buf, idx + 4);
          int remainStart = idx + 4, remainLen = d.headBuf.size - remainStart;
          d.haveHeaders = true;

          Map<String,String> hdrs = parseHeaders(headers);
          decideBodyMode(d, hdrs);

          if (fromClient) {
            String tid = HttpIds.ulid();
            c.pending.addLast(tid);
            String sid = sessionX.fromRequestHeaders(hdrs);
            if (sid != null) c.lastSession = sid;
            d.meta = new SegmentSink.Meta(tid, "REQ", c.lastSession,
                keyA(src, sport), keyB(dst, dport), d.tsFirst, d.tsLast);
            d.stream = streamSink.begin(d.meta, headers);
          } else {
            String tid = c.pending.isEmpty() ? HttpIds.ulid() : c.pending.peekFirst();
            String sid = sessionX.fromSetCookie(gatherSetCookies(headers));
            if (sid != null) c.lastSession = sid;
            d.meta = new SegmentSink.Meta(tid, "RSP", c.lastSession,
                keyA(dst, dport), keyB(src, sport), d.tsFirst, d.tsLast);
            d.stream = streamSink.begin(d.meta, headers);
          }

          // move already-buffered body bytes then clear headBuf
          if (remainLen > 0) feedBodyFixed(d, d.headBuf.buf, remainStart, remainLen);
          d.headBuf.reset();

          // leftover from this packet is body
          if (rem > 0) feedBodyFixed(d, p, pos, rem);

          // consume any in-order OOO into body path now
          drainOooIntoBuf(d, /*toBody*/true);
          drainBodyToSink(d);
        } else {
          // Header buffer full and still no CRLFCRLF => not HTTP
          if (d.headBuf.isFull()) { d.neverHttp = true; resetDir(d); return; }
          if (wrote == 0) { d.neverHttp = true; resetDir(d); return; }
        }
      }
      if (!d.haveHeaders) {
        // still waiting for headers
        if (fin) { d.seenFin = true; }
        return;
      }
    } else {
      // already have headers: this payload is body
      feedBodyFixed(d, p, off, len);
      drainOooIntoBuf(d, /*toBody*/true);
      drainBodyToSink(d);
    }

    if (fin) {
      d.seenFin = true;
      if (c.c2s.isIdle() && c.s2c.isIdle()) flows.remove(findKeyForConn(c), c);
    }
  }

  // --- Body staging with fixed cap (no growth) ---
  /** Feed body bytes under fixed cap. Applies tiny backpressure if full, then drains. */
  private void feedBodyFixed(Dir d, byte[] a, int off, int len){
    int pos = off, rem = len;
    while (rem > 0) {
      int wrote = d.bodyBuf.writeSome(a, pos, rem);
      pos += wrote; rem -= wrote;
      if (d.bodyBuf.isFull() && wrote == 0 && BACKPRESSURE_NS > 0) {
        LockSupport.parkNanos(BACKPRESSURE_NS);
      }
      if (d.bodyMode != BodyMode.CHUNKED) {
        drainBodyToSink(d);
        if (d.stream == null) break;
      } else {
        int progress;
        do { progress = consumeChunkedStreaming(d); } while (progress > 0);
        if (d.bodyMode == BodyMode.DONE){
          d.meta.tsLast = d.tsLast;
          streamSink.end(d.stream, d.tsLast);
          d.stream = null;
          resetDir(d);
          break;
        }
      }
    }
  }

  /** Drain any in-order OOO bytes into header/body staging (respects caps). */
  private static void drainOooIntoBuf(Dir d, boolean toBody){
    while (true){
      var e = d.ooo.firstEntry(); if (e==null) break;
      if (e.getKey() != d.nextSeq) break;
      byte[] v = e.getValue();
      int n = v.length;
      if (!toBody && !d.haveHeaders) {
        int wrote = d.headBuf.writeSome(v, 0, n);
        releaseOoo(n);
        d.oooBytes -= n;
        d.ooo.pollFirstEntry();
        d.nextSeq += n;
        if (wrote < n) { d.neverHttp = true; return; }
      } else {
        int wrote = d.bodyBuf.writeSome(v, 0, n);
        releaseOoo(n);
        d.oooBytes -= n;
        d.ooo.pollFirstEntry();
        d.nextSeq += n;
        if (wrote < n) { d.neverHttp = true; return; }
      }
    }
  }

  /** Drain d.bodyBuf to streamSink according to the active body mode. */
  private void drainBodyToSink(Dir d){
    if (d.stream == null) return;

    if (d.bodyMode == BodyMode.FIXED){
      while (d.bodyRemain > 0 && d.bodyBuf.size > 0){
        int n = Math.min(d.bodyRemain, d.bodyBuf.size);
        byte[] chunk = d.bodyBuf.readExact(n);
        streamSink.append(d.stream, chunk, 0, n);
        d.bodyRemain -= n;
      }
      if (d.bodyRemain == 0){
        d.meta.tsLast = d.tsLast;
        streamSink.end(d.stream, d.tsLast);
        d.stream = null;
        resetDir(d);
      }

    } else if (d.bodyMode == BodyMode.CHUNKED){
      int progress;
      do { progress = consumeChunkedStreaming(d); } while (progress > 0);
      if (d.bodyMode == BodyMode.DONE){
        d.meta.tsLast = d.tsLast;
        streamSink.end(d.stream, d.tsLast);
        d.stream = null;
        resetDir(d);
      }

    } else { // NONE
      d.meta.tsLast = d.tsLast;
      streamSink.end(d.stream, d.tsLast);
      d.stream = null;
      resetDir(d);
    }
  }

  // --- Chunked streaming consumer ---
  private int consumeChunkedStreaming(Dir d){
    if (d.bodyMode != BodyMode.CHUNKED) return 0;

    if (d.chunkRemain < 0 && !d.inTrailers){
      int idx = indexOfCrlf(d.bodyBuf.buf, 0, d.bodyBuf.size);
      if (idx < 0) return 0;
      int lineLen = idx + 2;
      byte[] line = d.bodyBuf.readExact(lineLen);
      streamSink.append(d.stream, line, 0, line.length);

      String s = new String(line, 0, lineLen-2, StandardCharsets.ISO_8859_1).trim();
      int semi = s.indexOf(';');
      String hex = (semi>=0? s.substring(0, semi): s).trim();
      int k=0; while (k<hex.length() && Character.digit(hex.charAt(k),16)>=0) k++;
      if (k==0) return 1;
      int size=0; for (int i=0;i<k;i++) size = (size<<4) + Character.digit(hex.charAt(i),16);
      d.chunkRemain = size;
      d.needCRLF = false;
      if (size==0){ d.inTrailers = true; }
      return 1;
    }

    if (!d.inTrailers && d.chunkRemain > 0){
      if (d.bodyBuf.size == 0) return 0;
      int n = Math.min(d.chunkRemain, d.bodyBuf.size);
      byte[] chunk = d.bodyBuf.readExact(n);
      streamSink.append(d.stream, chunk, 0, n);
      d.chunkRemain -= n;
      if (d.chunkRemain == 0) d.needCRLF = true;
      return 1;
    }

    if (!d.inTrailers && d.chunkRemain == 0 && d.needCRLF){
      if (d.bodyBuf.size < 2) return 0;
      byte[] crlf = d.bodyBuf.readExact(2);
      streamSink.append(d.stream, crlf, 0, 2);
      d.chunkRemain = -1;
      d.needCRLF = false;
      return 1;
    }

    if (d.inTrailers){
      int idx2 = indexOfCrlfCrlf(d.bodyBuf.buf, 0, d.bodyBuf.size);
      if (idx2 < 0){
        int eol = indexOfCrlf(d.bodyBuf.buf, 0, d.bodyBuf.size);
        if (eol > 0){
          int n = eol + 2;
          byte[] part = d.bodyBuf.readExact(n);
          streamSink.append(d.stream, part, 0, n);
          return 1;
        }
        return 0;
      }
      int len = findCrlfCrlfLen(d.bodyBuf.buf, d.bodyBuf.size);
      if (len == 0) return 0;
      byte[] trailers = d.bodyBuf.readExact(len);
      streamSink.append(d.stream, trailers, 0, trailers.length);
      d.bodyMode = BodyMode.DONE;
      return 1;
    }
    return 0;
  }

  // ---- Eviction / budgets ----
  private static boolean reserveOoo(int n){
    while (true) {
      long cur = OOO_INUSE.get();
      long next = cur + n;
      if (next > MAX_OOO_GLOBAL) return false;
      if (OOO_INUSE.compareAndSet(cur, next)) return true;
    }
  }
  private static void releaseOoo(int n){ OOO_INUSE.addAndGet(-n); }

  private void sweepEvictions(long nowNs){
    for (var e : flows.entrySet()){
      Conn cc = e.getValue();
      if (nowNs - cc.lastSeenNs > IDLE_NS && cc.c2s.isIdle() && cc.s2c.isIdle()) {
        flows.remove(e.getKey(), cc);
      }
    }
    int size = flows.size();
    if (size > MAX_CONNS){
      int target = (int)(size - (MAX_CONNS * 0.9));
      int removed = 0;
      for (var e : flows.entrySet()){
        if (removed >= target) break;
        Conn cc = e.getValue();
        if (cc.c2s.isIdle() && cc.s2c.isIdle()) {
          if (flows.remove(e.getKey(), cc)) removed++;
        }
      }
    }
  }

  // ---- Internals ----

  private final HttpStreamSink streamSink;
  private final SessionIdExtractor sessionX;
  private final ConcurrentHashMap<FlowKey, Conn> flows = new ConcurrentHashMap<>();
  private int evictTick = 0;

  // hot-path 2-slot flow cache
  private final LastFlow last0 = new LastFlow();
  private final LastFlow last1 = new LastFlow();

  // scratch key to avoid alloc on get(); never put into map
  private static final ThreadLocal<FlowKey> SCRATCH = ThreadLocal.withInitial(FlowKey::new);

  // negative cache
  private final NegCache neg = new NegCache(NEG_CACHE_LOG2);

  // Methods list for client first line
  private static final Set<String> METHODS = Set.of(
      "GET","POST","PUT","HEAD","DELETE","OPTIONS","PATCH","TRACE","CONNECT");

  private enum BodyMode { NONE, FIXED, CHUNKED, DONE }

  private static final class Dir {
    final TreeMap<Long, byte[]> ooo = new TreeMap<>();
    int oooBytes = 0;
    long nextSeq = -1L;

    // FIXED (pre-sized) staging buffers: never grow, never allocate after ctor
    final ByteArray headBuf = ByteArray.headerBuffer(MAX_HEADER_BYTES + 4);
    final ByteArray bodyBuf = ByteArray.bodyBuffer(BODY_BUF_CAP);

    boolean haveHeaders=false;
    BodyMode bodyMode = BodyMode.NONE;
    int bodyRemain=0;

    int chunkRemain=-1;
    boolean needCRLF=false;
    boolean inTrailers=false;

    long tsFirst, tsLast;

    boolean candidateChecked=false, looksHttp=false, neverHttp=false;

    SegmentSink.Meta meta;
    HttpStreamSink.HttpStream stream;

    boolean seenFin=false;

    boolean isIdle(){
      return stream == null
          && !haveHeaders
          && headBuf.size == 0
          && bodyBuf.size == 0
          && ooo.isEmpty();
    }
  }

  private static final class Conn {
    final Dir c2s = new Dir();
    final Dir s2c = new Dir();
    final Deque<String> pending = new ArrayDeque<>();
    String lastSession = null;
    volatile long lastSeenNs = System.nanoTime();
  }

  // Flow map key; frozen for map entries, mutable for scratch lookups
  private static final class FlowKey {
    String a; int ap; String b; int bp;
    int hash; boolean frozen;

    FlowKey(){}

    void set(String a, int ap, String b, int bp){
      if (frozen) throw new IllegalStateException("frozen");
      this.a=a; this.ap=ap; this.b=b; this.bp=bp;
      this.hash = mix4(a, ap, b, bp);
    }

    FlowKey frozenCopy(){
      FlowKey k = new FlowKey();
      k.a=this.a; k.ap=this.ap; k.b=this.b; k.bp=this.bp;
      k.hash=this.hash; k.frozen=true;
      return k;
    }

    @Override public int hashCode(){ return hash; }
    @Override public boolean equals(Object o){
      if (this == o) return true;
      if (!(o instanceof FlowKey other)) return false;
      return ap==other.ap && bp==other.bp
          && a.equals(other.a) && b.equals(other.b);
    }
  }

  private static final class LastFlow {
    String a; int ap; String b; int bp; boolean fromClient;
    Conn conn;

    boolean matches(String sa, int sap, String sb, int sbp, boolean fc){
      if (conn == null) return false;
      if (fc != fromClient) return false;
      return a==sa && ap==sap && b==sb && bp==sbp; // reference + value comparison
    }
    void setFrom(String sa, int sap, String sb, int sbp, boolean fc, Conn c){
      this.a=sa; this.ap=sap; this.b=sb; this.bp=sbp; this.fromClient=fc; this.conn=c;
    }
    void swapInto(LastFlow other){
      // move this into 'other'
      other.a=this.a; other.ap=this.ap; other.b=this.b; other.bp=this.bp; other.fromClient=this.fromClient; other.conn=this.conn;
    }
  }

  // --- Negative cache for non-HTTP 4-tuples (direction-agnostic) ---
  private static final class NegCache {
    private final long[] keys;
    private final long[] expiry;
    private final int mask;

    NegCache(int log2){
      int cap = 1 << Math.max(8, Math.min(24, log2)); // clamp
      this.keys = new long[cap];
      this.expiry = new long[cap];
      this.mask = cap - 1;
    }
    boolean has(long key, long now){
      int idx = (int) key & mask;
      return keys[idx] == key && expiry[idx] > now;
    }
    void put(long key, long now, long ttl){
      int idx = (int) key & mask;
      keys[idx] = key;
      expiry[idx] = now + ttl;
    }
  }
  private boolean negCached(long k, long now){ return neg.has(k, now); }
  private void   putNeg(long k, long now){ neg.put(k, now, NEG_TTL_NS); }

  // ---- Helpers ----
  private static int mix4(String a, int ap, String b, int bp){
    int h = 0x9E3779B9;
    h ^= (a!=null ? a.hashCode() : 0); h = (h<<5) - h;
    h ^= ap;                               h = (h<<5) - h;
    h ^= (b!=null ? b.hashCode() : 0); h = (h<<5) - h;
    h ^= bp;                               h = (h<<5) - h;
    return h;
  }
  private static String keyA(String ip, int port){ return ip + ":" + port; }
  private static String keyB(String ip, int port){ return ip + ":" + port; }

  private static boolean looksLikeHttpFirstLine(byte[] buf, int off, int len, boolean fromClient){
    int end = Math.min(off+len, off+96);
    int i=off; while (i<end && buf[i]!=13 && buf[i]!=10) i++;
    int lineLen = i-off; if (lineLen<4) return false;
    String s = new String(buf, off, Math.min(lineLen, 16), StandardCharsets.US_ASCII);
    if (fromClient){ for (String m: METHODS) if (s.startsWith(m+" ")) return true; return false; }
    else return s.startsWith("HTTP/1.");
  }

  private static int indexOfCrlfCrlf(byte[] a, int off, int len){
    int end=off+len-3;
    for (int i=off;i<=end;i++) if (a[i]==13 && a[i+1]==10 && a[i+2]==13 && a[i+3]==10) return i+3;
    return -1;
  }
  private static int indexOfCrlf(byte[] a, int off, int len){
    int end = off + len - 1;
    for (int i=off;i<end;i++) if (a[i]==13 && a[i+1]==10) return i;
    return -1;
  }
  private static int findCrlfCrlfLen(byte[] a, int size){
    for (int i=0;i+3<=size-1;i++){
      if (a[i]==13 && a[i+1]==10 && a[i+2]==13 && a[i+3]==10) return i+4;
    }
    return 0;
  }

  private static Map<String,String> parseHeaders(byte[] head){
    Map<String,String> m = new LinkedHashMap<>();
    int i=0, n=head.length;
    for (; i+1<n; i++) if (head[i]==13 && head[i+1]==10){ i+=2; break; } // skip request/status line
    String key=null; StringBuilder val=new StringBuilder(64);
    for (; i<n; ){
      if (i+1<n && head[i]==13 && head[i+1]==10){ if (key!=null) m.put(key.toLowerCase(Locale.ROOT), val.toString()); break; }
      int s=i; while (i<n && head[i]!=13) i++;
      String line = new String(head, s, i-s, StandardCharsets.ISO_8859_1); i+=2;
      if (line.isEmpty()) break;
      if ((line.startsWith(" ")||line.startsWith("\t")) && key!=null){ val.append(' ').append(line.trim()); continue; }
      int c=line.indexOf(':');
      if (c>0){ if (key!=null) m.put(key.toLowerCase(Locale.ROOT), val.toString());
        key=line.substring(0,c).trim(); val.setLength(0); val.append(line.substring(c+1).trim()); }
    }
    return m;
  }

  private static List<String> gatherSetCookies(byte[] head){
    List<String> out = new ArrayList<>();
    int i=0, n=head.length;
    for (; i+1<n; i++) if (head[i]==13 && head[i+1]==10){ i+=2; break; }
    for (; i+1<n; ){
      int s=i; while (i<n && head[i]!=13) i++;
      String line = new String(head, s, i-s, StandardCharsets.ISO_8859_1); i+=2;
      int c=line.indexOf(':');
      if (c>0 && line.regionMatches(true, 0, "Set-Cookie", 0, "Set-Cookie".length()))
        out.add(line.substring(c+1).trim());
    }
    return out;
  }

  private static void decideBodyMode(Dir d, Map<String,String> hdrs){
    String te = hdrs.get("transfer-encoding");
    if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")){
      d.bodyMode = BodyMode.CHUNKED;
      d.chunkRemain = -1;
      d.needCRLF = false;
      d.inTrailers = false;
      return;
    }
    String cl = hdrs.get("content-length");
    if (cl != null){
      d.bodyMode = BodyMode.FIXED;
      d.bodyRemain = safeParseInt(cl.trim(), 0);
    } else {
      d.bodyMode = BodyMode.NONE;
      d.bodyRemain = 0;
    }
  }

  private static int safeParseInt(String s, int def){ try { return Integer.parseInt(s); } catch (Exception e){ return def; } }

  private static void resetDir(Dir d){
    d.haveHeaders=false; d.bodyMode=BodyMode.NONE; d.bodyRemain=0;
    d.chunkRemain=-1; d.needCRLF=false; d.inTrailers=false;
    d.meta=null; d.stream=null;
    d.candidateChecked=false; d.looksHttp=false;
    // release global OOO
    for (byte[] v : d.ooo.values()) releaseOoo(v.length);
    d.ooo.clear(); d.oooBytes = 0;
    d.headBuf.reset(); d.bodyBuf.reset();
  }

  // --- Fixed-capacity ByteArray (kept name for drop-in compatibility) ---
  static final class ByteArray {
    final byte[] buf;    // fixed-size backing
    int size;            // current fill [0..buf.length]

    static ByteArray headerBuffer(int maxBytes) { return new ByteArray(maxBytes); }
    static ByteArray bodyBuffer(int maxBytes)   { return new ByteArray(maxBytes); }

    // Pre-size to maximum; NEVER grows again.
    ByteArray(int capacity){
      int cap = Math.max(256, capacity);
      this.buf = new byte[cap];
      this.size = 0;
    }

    /** Write up to len bytes; NEVER exceed capacity. Returns bytes actually written (possibly 0). */
    int writeSome(byte[] p, int off, int len){
      if (len <= 0) return 0;
      int allowed = Math.min(len, buf.length - size);
      if (allowed <= 0) return 0;
      System.arraycopy(p, off, buf, size, allowed);
      size += allowed;
      return allowed;
    }

    // Legacy signature kept for compatibility; funnels to capped write.
    void write(byte[] p, int off, int len){ writeSome(p, off, len); }

    byte[] readExact(int n){
      byte[] out = new byte[n];
      System.arraycopy(buf, 0, out, 0, n);
      int rem = size - n;
      if (rem > 0) System.arraycopy(buf, n, buf, 0, rem);
      size = rem;
      return out;
    }
    void reset(){ size = 0; }
    boolean isFull(){ return size >= buf.length; }
  }

  // --- Utility: find map key for removal (only called when both dirs idle) ---
  private FlowKey findKeyForConn(Conn c){
    // We don't store reverse index; remove by scanning a few keys (rare)
    for (var e : flows.entrySet()){
      if (e.getValue() == c) return e.getKey();
    }
    return null;
  }

  // --- Negative-cache key (direction-agnostic) ---
  private static long negKey(String src, int sport, String dst, int dport){
    int h1 = mix2(src, sport);
    int h2 = mix2(dst, dport);
    int lo = Math.min(h1, h2);
    int hi = Math.max(h1, h2);
    return ((long)hi << 32) ^ (lo & 0xFFFFFFFFL);
  }
  private static int mix2(String s, int p){
    int h = 0x7F4A7C15;
    h ^= (s!=null? s.hashCode():0); h = (h<<5) - h;
    h ^= p;                          h = (h<<5) - h;
    return h;
  }

  private static HttpStreamSink defaultStreamSink(java.nio.file.Path baseDir) {
    try {
      return new SegmentSink(SegmentSink.Config.hourlyGiB(baseDir));
    } catch (java.io.IOException e) {
      throw new RuntimeException("Failed to create default SegmentSink for " + baseDir, e);
    }
  }
}
