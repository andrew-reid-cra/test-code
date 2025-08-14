package sniffer.domain;

import static sniffer.common.Bytes.*;

public final class Decoders {
  private Decoders(){}
  public static boolean isIpv4(byte[] p, int caplen){
    return caplen >= 14 && u16be(p, 12) == 0x0800;
  }
  public static int ipv4HeaderLen(byte[] p, int ipOff){
    int vihl = u8(p, ipOff);
    if ((vihl >>> 4) != 4) return -1;
    return (vihl & 0x0F) * 4;
  }
}
