package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.domain.util.Bytes;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;

public final class FrameDecoderLibpcap implements FrameDecoder {

  @Override
  public Optional<TcpSegment> decode(RawFrame frame) {
    if (frame == null) return Optional.empty();
    byte[] pkt = frame.data();
    int caplen = pkt.length;
    if (caplen < 14) return Optional.empty();

    int etherType = Bytes.u16be(pkt, 12);
    int offset = 14;

    // VLAN tags
    if (etherType == 0x8100 || etherType == 0x88A8) {
      if (caplen < offset + 4) return Optional.empty();
      etherType = Bytes.u16be(pkt, offset + 2);
      offset += 4;
    }

    if (etherType == 0x0800) {
      return decodeIpv4(pkt, caplen, offset, frame.timestampMicros());
    } else if (etherType == 0x86DD) {
      return decodeIpv6(pkt, caplen, offset, frame.timestampMicros());
    }
    return Optional.empty();
  }

  private Optional<TcpSegment> decodeIpv4(byte[] pkt, int caplen, int offset, long timestampMicros) {
    if (caplen < offset + 20) return Optional.empty();
    int vihl = Bytes.u8(pkt, offset);
    if ((vihl >>> 4) != 4) return Optional.empty();
    int ihl = (vihl & 0x0F) * 4;
    if (caplen < offset + ihl) return Optional.empty();

    int proto = Bytes.u8(pkt, offset + 9);
    if (proto != 6) return Optional.empty();

    int totalLen = Bytes.u16be(pkt, offset + 2);
    int ipPayload = Math.max(0, Math.min(totalLen - ihl, caplen - offset - ihl));

    String src = ipv4(pkt, offset + 12);
    String dst = ipv4(pkt, offset + 16);

    offset += ihl;
    return decodeTcp(pkt, caplen, offset, ipPayload, src, dst, timestampMicros);
  }

  private Optional<TcpSegment> decodeIpv6(byte[] pkt, int caplen, int offset, long timestampMicros) {
    if (caplen < offset + 40) return Optional.empty();
    int nextHdr = Bytes.u8(pkt, offset + 6);
    if (nextHdr != 6) return Optional.empty();

    int payloadLen = Bytes.u16be(pkt, offset + 4);
    int ipPayload = Math.max(0, Math.min(payloadLen, caplen - offset - 40));

    String src = ipv6(pkt, offset + 8);
    String dst = ipv6(pkt, offset + 24);

    offset += 40;
    return decodeTcp(pkt, caplen, offset, ipPayload, src, dst, timestampMicros);
  }

  private Optional<TcpSegment> decodeTcp(
      byte[] pkt,
      int caplen,
      int offset,
      int ipPayload,
      String src,
      String dst,
      long timestampMicros) {
    if (caplen < offset + 20) return Optional.empty();

    int sport = Bytes.u16be(pkt, offset);
    int dport = Bytes.u16be(pkt, offset + 2);
    long seq = Bytes.u32be(pkt, offset + 4) & 0xFFFFFFFFL;

    int dataOff = (Bytes.u8(pkt, offset + 12) >>> 4) * 4;
    if (dataOff < 20 || caplen < offset + dataOff) return Optional.empty();

    int flags = Bytes.u8(pkt, offset + 13);
    boolean fin = (flags & 0x01) != 0;
    boolean syn = (flags & 0x02) != 0;
    boolean rst = (flags & 0x04) != 0;
    boolean psh = (flags & 0x08) != 0;
    boolean ack = (flags & 0x10) != 0;

    int payloadOffset = offset + dataOff;
    if (payloadOffset > caplen) return Optional.empty();

    int payloadLen = Math.max(0, Math.min(caplen - payloadOffset, ipPayload - dataOff));
    byte[] payload = new byte[payloadLen];
    if (payloadLen > 0) {
      System.arraycopy(pkt, payloadOffset, payload, 0, payloadLen);
    }

    FiveTuple tuple = new FiveTuple(src, sport, dst, dport, "TCP");
    TcpSegment segment =
        new TcpSegment(tuple, seq, true, payload, fin, syn, rst, psh, ack, timestampMicros);
    return Optional.of(segment);
  }

  private static String ipv4(byte[] p, int off) {
    return (Bytes.u8(p, off))
        + "." + Bytes.u8(p, off + 1)
        + "." + Bytes.u8(p, off + 2)
        + "." + Bytes.u8(p, off + 3);
  }

  private static String ipv6(byte[] p, int off) {
    try {
      return InetAddress.getByAddress(Arrays.copyOfRange(p, off, off + 16)).getHostAddress();
    } catch (UnknownHostException e) {
      return "::";
    }
  }
}
