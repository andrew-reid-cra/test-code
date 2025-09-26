package ca.gc.cra.radar.domain.net;

import java.util.Objects;

/**
 * <strong>What:</strong> Immutable five-tuple identifying a TCP flow (source/destination IP and port plus protocol label).
 * <p><strong>Why:</strong> Provides a canonical handle for tracking conversations across capture, assemble, and sink stages.</p>
 * <p><strong>Role:</strong> Domain value object used as a key in flow-oriented maps and metrics.</p>
 * <p><strong>Thread-safety:</strong> Immutable; safe for concurrent sharing.</p>
 * <p><strong>Performance:</strong> Stores primitive fields and strings; suitable for hot-path lookups.</p>
 * <p><strong>Observability:</strong> Values surface in logs and metrics tags (e.g., {@code flow.srcIp}).</p>
 *
 * @since 0.1.0
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
   * @param srcIp source IP address; must not be {@code null}
   * @param srcPort source TCP port (0-65535)
   * @param dstIp destination IP address; must not be {@code null}
   * @param dstPort destination TCP port (0-65535)
   * @param protocol transport protocol label (defaults to {@code "TCP"} when {@code null})
   *
   * <p><strong>Concurrency:</strong> Safe to call from any thread; resulting instance is immutable.</p>
   * <p><strong>Performance:</strong> Constant-time field assignments.</p>
   * <p><strong>Observability:</strong> Callers should sanitize IP strings before logging.</p>
   */
  public FiveTuple(String srcIp, int srcPort, String dstIp, int dstPort, String protocol) {
    this.srcIp = Objects.requireNonNull(srcIp, "srcIp");
    this.srcPort = srcPort;
    this.dstIp = Objects.requireNonNull(dstIp, "dstIp");
    this.dstPort = dstPort;
    this.protocol = protocol == null ? "TCP" : protocol;
  }

  /**
   * Returns the source IP address.
   *
   * @return source IP address string
   *
   * <p><strong>Concurrency:</strong> Safe to call concurrently.</p>
   * <p><strong>Performance:</strong> Constant-time accessor.</p>
   * <p><strong>Observability:</strong> Value is typically logged as {@code flow.srcIp}.</p>
   */
  public String srcIp() { return srcIp; }

  /**
   * Returns the source TCP port.
   *
   * @return numeric source port
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor.</p>
   * <p><strong>Performance:</strong> Constant-time.</p>
   * <p><strong>Observability:</strong> Useful for metrics segmentation (e.g., {@code flow.srcPort}).</p>
   */
  public int srcPort() { return srcPort; }

  /**
   * Returns the destination IP address.
   *
   * @return destination IP address string
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor.</p>
   * <p><strong>Performance:</strong> Constant-time.</p>
   * <p><strong>Observability:</strong> Recorded in logs for flow identification.</p>
   */
  public String dstIp() { return dstIp; }

  /**
   * Returns the destination TCP port.
   *
   * @return numeric destination port
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor.</p>
   * <p><strong>Performance:</strong> Constant-time.</p>
   * <p><strong>Observability:</strong> Often used for protocol hints.</p>
   */
  public int dstPort() { return dstPort; }

  /**
   * Returns the transport protocol label (typically {@code "TCP"}).
   *
   * @return protocol label string
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor.</p>
   * <p><strong>Performance:</strong> Constant-time.</p>
   * <p><strong>Observability:</strong> Useful for tagging capture metrics by transport.</p>
   */
  public String protocol() { return protocol; }
}
