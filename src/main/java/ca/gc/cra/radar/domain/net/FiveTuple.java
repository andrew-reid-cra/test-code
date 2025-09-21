package ca.gc.cra.radar.domain.net;

import java.util.Objects;

/**
 * Immutable five-tuple identifying a TCP flow (source/destination IP and port plus protocol label).
 *
 * @since RADAR 0.1-doc
 */
public final class FiveTuple {
  private final String srcIp;
  private final int srcPort;
  private final String dstIp;
  private final int dstPort;
  private final String protocol; // e.g., "TCP"

  /**
   * Creates a five-tuple descriptor.
   *
   * @param srcIp source IP address
   * @param srcPort source TCP port
   * @param dstIp destination IP address
   * @param dstPort destination TCP port
   * @param protocol transport protocol label (defaults to {@code "TCP"} when {@code null})
   * @since RADAR 0.1-doc
   */
  public FiveTuple(String srcIp, int srcPort, String dstIp, int dstPort, String protocol) {
    this.srcIp = Objects.requireNonNull(srcIp, "srcIp");
    this.srcPort = srcPort;
    this.dstIp = Objects.requireNonNull(dstIp, "dstIp");
    this.dstPort = dstPort;
    this.protocol = protocol == null ? "TCP" : protocol;
  }

  /**
   * Gets the source IP address.
   *
   * @return source IP address
   * @since RADAR 0.1-doc
   */
  public String srcIp() { return srcIp; }

  /**
   * Gets the source TCP port.
   *
   * @return source TCP port
   * @since RADAR 0.1-doc
   */
  public int srcPort() { return srcPort; }

  /**
   * Gets the destination IP address.
   *
   * @return destination IP address
   * @since RADAR 0.1-doc
   */
  public String dstIp() { return dstIp; }

  /**
   * Gets the destination TCP port.
   *
   * @return destination TCP port
   * @since RADAR 0.1-doc
   */
  public int dstPort() { return dstPort; }

  /**
   * Gets the transport protocol label (typically {@code "TCP"}).
   *
   * @return transport protocol label (typically {@code "TCP"})
   * @since RADAR 0.1-doc
   */
  public String protocol() { return protocol; }
}
