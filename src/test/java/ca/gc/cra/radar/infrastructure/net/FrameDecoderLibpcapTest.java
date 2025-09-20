package ca.gc.cra.radar.infrastructure.net;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.net.RawFrame;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FrameDecoderLibpcapTest {
  @Test
  void decodesSimpleIpv4TcpPacket() {
    byte[] pkt = buildPacket();
    FrameDecoderLibpcap decoder = new FrameDecoderLibpcap();
    Optional<ca.gc.cra.radar.domain.net.TcpSegment> segment = decoder.decode(new RawFrame(pkt, 0));
    assertTrue(segment.isPresent());
    var seg = segment.get();
    assertEquals("1.1.1.1", seg.flow().srcIp());
    assertEquals("2.2.2.2", seg.flow().dstIp());
    assertEquals(1234, seg.flow().srcPort());
    assertEquals(80, seg.flow().dstPort());
    assertTrue(seg.ack());
    assertFalse(seg.fin());
    assertFalse(seg.syn());
    assertEquals(0L, seg.timestampMicros());
  }

  private static byte[] buildPacket() {
    byte[] pkt = new byte[14 + 20 + 20];
    int i = 12;
    pkt[i++] = 0x08;
    pkt[i++] = 0x00;

    // IPv4 header
    pkt[i++] = 0x45; // version + IHL
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x28; // total length 40
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x40;
    pkt[i++] = 0x00;
    pkt[i++] = 64;
    pkt[i++] = 6; // TCP
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 1;
    pkt[i++] = 1;
    pkt[i++] = 1;
    pkt[i++] = 1;
    pkt[i++] = 2;
    pkt[i++] = 2;
    pkt[i++] = 2;
    pkt[i++] = 2;

    // TCP header
    pkt[i++] = 0x04; // src port 1234
    pkt[i++] = (byte) 0xD2;
    pkt[i++] = 0x00;
    pkt[i++] = 0x50; // dst port 80
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x01;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x50; // data offset 5
    pkt[i++] = 0x10; // ACK
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;
    pkt[i++] = 0x00;

    return pkt;
  }
}
