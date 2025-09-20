package ca.gc.cra.radar.domain.net;

import java.util.Objects;

/** 5-tuple identifying a TCP flow. */
public final class FiveTuple {
  private final String srcIp;
  private final int srcPort;
  private final String dstIp;
  private final int dstPort;
  private final String protocol; // e.g., "TCP"

  public FiveTuple(String srcIp, int srcPort, String dstIp, int dstPort, String protocol) {
    this.srcIp = Objects.requireNonNull(srcIp, "srcIp");
    this.srcPort = srcPort;
    this.dstIp = Objects.requireNonNull(dstIp, "dstIp");
    this.dstPort = dstPort;
    this.protocol = protocol == null ? "TCP" : protocol;
  }

  public String srcIp() { return srcIp; }

  public int srcPort() { return srcPort; }

  public String dstIp() { return dstIp; }

  public int dstPort() { return dstPort; }

  public String protocol() { return protocol; }
}


