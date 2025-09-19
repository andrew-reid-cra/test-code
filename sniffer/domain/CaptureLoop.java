package sniffer.domain;

import sniffer.domain.tn3270.Tn3270Assembler;
import sniffer.spi.Pcap;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static sniffer.common.Bytes.*;

public final class CaptureLoop {
  private final Pcap pcap;
  private final PacketSink sink;
  private final boolean showHeaders;

  private final HttpAssembler httpAsm;
  private final SessionIdExtractor sessionExtractor;
  private final Tn3270Assembler tn3270Asm;

  private final ConcurrentMap<FlowKey, FlowDir> flowDirs = new ConcurrentHashMap<>();

  public interface PacketSink {
    void onHttpLine(long tsMicros, String text);
    void onUdp(long tsMicros, String src, int sport, String dst, int dport);
  }

  public CaptureLoop(Pcap pcap, PacketSink sink, boolean showHeaders,
                     SegmentSink httpSink, SegmentSink tnSink){
    this.pcap = pcap;
    this.sink = sink;
    this.showHeaders = showHeaders;

    if (httpSink != null) {
      this.sessionExtractor = new SessionIdExtractor(
          java.util.List.of("JSESSIONID","SESSIONID","PHPSESSID","ASP.NET_SessionId"),
          java.util.List.of("Authorization\\s*:\\s*Bearer\\s+(\\S+)")
      );
      this.httpAsm = new HttpAssembler((HttpStreamSink) httpSink, sessionExtractor);
    } else {
      this.sessionExtractor = null;
      this.httpAsm = null;
    }

    this.tn3270Asm = (tnSink != null) ? new Tn3270Assembler(tnSink) : null;
  }

  public void runForever(String iface, int bufMb, int snap, int timeoutMs, String bpf) throws Exception {
    try (var h = pcap.openLive(iface, snap, true, timeoutMs, bufMb * 1024 * 1024, true)) {
      if (bpf != null && !bpf.isEmpty()) h.setFilter(bpf);
      while (h.next((tsMicros, data, caplen) -> handle(tsMicros, data, caplen))) { /* loop */ }
    }
  }

  private void handle(long tsMicros, byte[] pkt, int caplen){
    if (caplen < 14) return;

    // --- Ethernet + optional VLAN tag peeling ---
    int etherType = u16be(pkt, 12);
    int l2 = 14;

    // Peel one or more VLAN tags (0x8100 = 802.1Q, 0x88A8 = 802.1ad QinQ)
    if (etherType == 0x8100 || etherType == 0x88A8) {
      int idx = 14;
      while (true) {
        if (caplen < idx + 4) return;           // truncated
        etherType = u16be(pkt, idx + 2);        // next ethertype after TCI
        idx += 4; l2 = idx;
        if (etherType != 0x8100 && etherType != 0x88A8) break;
      }
    }

    // IPv4 only
    if (etherType != 0x0800) return;

    final int ipOff = l2;
    final int vihl = u8(pkt, ipOff);
    if ((vihl >>> 4) != 4) return;
    final int ihl = (vihl & 0x0F) * 4;
    if (caplen < ipOff + ihl) return;

    final int proto = u8(pkt, ipOff+9);
    final int srcIpBE = u32be(pkt, ipOff+12), dstIpBE = u32be(pkt, ipOff+16);
    final int l4 = ipOff + ihl;

    if (proto == 6) { // TCP
      if (caplen < l4 + 20) return;

      final int sport = u16be(pkt, l4), dport = u16be(pkt, l4+2);
      final int doff = ((u8(pkt, l4+12) >>> 4) & 0x0F) * 4;
      final int pay = l4 + doff;
      if (pay > caplen) return;

      final int flags = u8(pkt, l4+13);
      final boolean syn = (flags & 0x02) != 0;
      final boolean ack = (flags & 0x10) != 0;
      final boolean fin = (flags & 0x01) != 0;
      final long seq = (u32be(pkt, l4+4) & 0xFFFFFFFFL);

      FlowKey key = FlowKey.of(srcIpBE, sport, dstIpBE, dport);
      if (syn && !ack) {
        flowDirs.compute(key, (k, old) -> FlowDir.learnClient(k, srcIpBE, sport));
      }

      boolean fromClient = guessDirection(srcIpBE, sport, dstIpBE, dport);

      final int payloadLen = caplen - pay;
      if (payloadLen > 0) {
        if (httpAsm != null) {
          httpAsm.onTcpSegment(tsMicros,
              ip(srcIpBE), sport, ip(dstIpBE), dport,
              seq, fromClient, pkt, pay, payloadLen, fin);
        }

        if (tn3270Asm != null) {
          tn3270Asm.onTcpSegment(tsMicros,
              ip(srcIpBE), sport, ip(dstIpBE), dport,
              seq, fromClient, pkt, pay, payloadLen, fin);
        }

        // Only print if the payload looks like an HTTP first line
        if (looksLikeHttpFirstLine(pkt, pay, payloadLen)) {
          String text = HttpPrinter.firstLineAndMaybeHeaders(pkt, pay, caplen, showHeaders);
          sink.onHttpLine(tsMicros, "%d TAP %s:%d�+'%s:%d %s".formatted(
              tsMicros, ip(srcIpBE), sport, ip(dstIpBE), dport, text));
        }
      }

    } else if (proto == 17) { // UDP
      if (caplen < l4 + 8) return;
      int sport = u16be(pkt, l4), dport = u16be(pkt, l4+2);
      sink.onUdp(tsMicros, ip(srcIpBE), sport, ip(dstIpBE), dport);
    }
  }

  private boolean guessDirection(int srcIpBE, int sport, int dstIpBE, int dport){
    FlowKey key = FlowKey.of(srcIpBE, sport, dstIpBE, dport);
    FlowDir dir = flowDirs.get(key);
    if (dir != null && dir.known) return dir.isClient(srcIpBE, sport);
    return sport > dport; // heuristic until SYN is seen
  }

  private static boolean looksLikeHttpFirstLine(byte[] pkt, int off, int len){
    int end = Math.min(off + len, off + 96);
    int i = off;
    while (i < end && pkt[i] != '\r' && pkt[i] != '\n') i++;
    int lineLen = i - off;
    if (lineLen < 4) return false;
    String s = new String(pkt, off, Math.min(lineLen, 16), StandardCharsets.US_ASCII);
    return s.startsWith("GET ") || s.startsWith("POST ") || s.startsWith("PUT ")
        || s.startsWith("HEAD ") || s.startsWith("DELETE ") || s.startsWith("OPTIONS ")
        || s.startsWith("PATCH ") || s.startsWith("TRACE ") || s.startsWith("CONNECT ")
        || s.startsWith("HTTP/1.");
  }

  private static String ip(int be){
    int a=(be>>>24)&255,b=(be>>>16)&255,c=(be>>>8)&255,d=be&255;
    return a+"."+b+"."+c+"."+d;
  }

  // ====== Flow key/direction helpers ======
  private static final class FlowKey {
    final int aIp, aPort, bIp, bPort;
    private FlowKey(int aIp, int aPort, int bIp, int bPort){
      this.aIp=aIp; this.aPort=aPort; this.bIp=bIp; this.bPort=bPort;
    }
    static FlowKey of(int srcIp, int sport, int dstIp, int dport){
      if (less(srcIp, sport, dstIp, dport)) return new FlowKey(srcIp, sport, dstIp, dport);
      else return new FlowKey(dstIp, dport, srcIp, sport);
    }
    private static boolean less(int ip1, int p1, int ip2, int p2){
      if (ip1 != ip2) return (ip1 ^ 0x80000000) < (ip2 ^ 0x80000000);
      return p1 < p2;
    }
    @Override public boolean equals(Object o){
      if (this==o) return true;
      if (!(o instanceof FlowKey k)) return false;
      return aIp==k.aIp && aPort==k.aPort && bIp==k.bIp && bPort==k.bPort;
    }
    @Override public int hashCode(){ return Objects.hash(aIp, aPort, bIp, bPort); }
  }

  private static final class FlowDir {
    final int clientIp; final int clientPort; final boolean known;
    private FlowDir(int clientIp, int clientPort, boolean known){
      this.clientIp=clientIp; this.clientPort=clientPort; this.known=known;
    }
    static FlowDir learnClient(FlowKey k, int synSrcIp, int synSrcPort){
      return new FlowDir(synSrcIp, synSrcPort, true);
    }
    boolean isClient(int ip, int port){ return known && ip==clientIp && port==clientPort; }
  }
}
