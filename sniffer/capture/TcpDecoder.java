package sniffer.capture;

import static java.lang.Byte.toUnsignedInt;
import java.net.InetAddress;
import java.net.UnknownHostException;

final class TcpDecoder {
  static final class Decoded {
    String src, dst; int sport, dport;
    long seq; int flags;
    int payloadOff, payloadLen;
  }

  static Decoded decode(byte[] pkt, int caplen) {
    int off=0, rem=caplen;
    if (rem < 14) return null;

    int etherType = ((toUnsignedInt(pkt[12]) << 8) | toUnsignedInt(pkt[13]));
    off += 14; rem -= 14;

    // Single VLAN tag support
    if (etherType == 0x8100 || etherType == 0x88A8) {
      if (rem < 4) return null;
      etherType = ((toUnsignedInt(pkt[off+2]) << 8) | toUnsignedInt(pkt[off+3]));
      off += 4; rem -= 4;
    }

    if (etherType == 0x0800) { // IPv4
      if (rem < 20) return null;
      int vihl = toUnsignedInt(pkt[off]);
      int ihl = (vihl & 0x0F) * 4;
      if ((vihl >>> 4) != 4 || ihl < 20 || rem < ihl) return null;
      int proto = toUnsignedInt(pkt[off + 9]);
      if (proto != 6) return null;

      String src = ipv4(pkt, off + 12);
      String dst = ipv4(pkt, off + 16);
      int totalLen = ((toUnsignedInt(pkt[off + 2]) << 8) | toUnsignedInt(pkt[off + 3]));
      int ipPayload = Math.max(0, Math.min(totalLen - ihl, rem - ihl));
      off += ihl; rem -= ihl;
      return tcp(pkt, off, rem, ipPayload, src, dst);

    } else if (etherType == 0x86DD) { // IPv6
      if (rem < 40) return null;
      int nextHdr = toUnsignedInt(pkt[off + 6]);
      if (nextHdr != 6) return null;
      String src = ipv6(pkt, off + 8);
      String dst = ipv6(pkt, off + 24);
      int payloadLen = ((toUnsignedInt(pkt[off + 4]) << 8) | toUnsignedInt(pkt[off + 5]));
      int ipPayload = Math.max(0, Math.min(payloadLen, rem - 40));
      off += 40; rem -= 40;
      return tcp(pkt, off, rem, ipPayload, src, dst);
    }
    return null;
  }

  private static Decoded tcp(byte[] p, int off, int rem, int ipPayload, String src, String dst) {
    if (rem < 20) return null;
    int sport = ((toUnsignedInt(p[off]) << 8) | toUnsignedInt(p[off + 1]));
    int dport = ((toUnsignedInt(p[off + 2]) << 8) | toUnsignedInt(p[off + 3]));
    long seq = (((long)toUnsignedInt(p[off+4]) << 24) |
                ((long)toUnsignedInt(p[off+5]) << 16) |
                ((long)toUnsignedInt(p[off+6]) << 8)  |
                ((long)toUnsignedInt(p[off+7])));
    int offFlags = toUnsignedInt(p[off + 12]);
    int dataOff = (offFlags >>> 4) * 4;
    if (dataOff < 20 || rem < dataOff) return null;

    int flagsByte = toUnsignedInt(p[off + 13]);
    int flags = 0;
    if ((flagsByte & 0x01) != 0) flags |= sniffer.pipe.SegmentRecord.FIN;
    if ((flagsByte & 0x02) != 0) flags |= sniffer.pipe.SegmentRecord.SYN;
    if ((flagsByte & 0x04) != 0) flags |= sniffer.pipe.SegmentRecord.RST;
    if ((flagsByte & 0x08) != 0) flags |= sniffer.pipe.SegmentRecord.PSH;
    if ((flagsByte & 0x10) != 0) flags |= sniffer.pipe.SegmentRecord.ACK;

    int payloadOff = off + dataOff;
    int maxAvail = Math.min(rem, ipPayload);
    int payloadLen = Math.max(0, maxAvail - dataOff);
    if (payloadLen < 0) payloadLen = 0;

    Decoded d = new Decoded();
    d.src = src; d.dst = dst; d.sport = sport; d.dport = dport;
    d.seq = seq; d.flags = flags;
    d.payloadOff = payloadOff; d.payloadLen = Math.min(payloadLen, Math.max(0, p.length - payloadOff));
    return d;
  }

  private static String ipv4(byte[] p, int off){
    return (toUnsignedInt(p[off])   ) + "." +
           (toUnsignedInt(p[off+1]) ) + "." +
           (toUnsignedInt(p[off+2]) ) + "." +
           (toUnsignedInt(p[off+3]) );
  }

  private static String ipv6(byte[] p, int off){
    try { return InetAddress.getByAddress(java.util.Arrays.copyOfRange(p, off, off+16)).getHostAddress(); }
    catch (UnknownHostException e) { return "::"; }
  }

  private TcpDecoder() {}
}
