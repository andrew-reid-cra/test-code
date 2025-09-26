package ca.gc.cra.radar.domain.protocol;

/**
 * <strong>What:</strong> Known application protocols handled by RADAR.
 * <p><strong>Why:</strong> Used for routing flows to protocol modules and tagging observability data.</p>
 * <p><strong>Role:</strong> Domain enumeration referenced across capture, assemble, and sink components.</p>
 * <p><strong>Thread-safety:</strong> Enum constants are immutable and globally shareable.</p>
 * <p><strong>Performance:</strong> Constant-time comparisons and switch dispatch.</p>
 * <p><strong>Observability:</strong> Values surface as metric dimensions (e.g., {@code protocol=HTTP}).</p>
 *
 * @since 0.1.0
 */
public enum ProtocolId {
  /** Hypertext Transfer Protocol. */
  HTTP,
  /** IBM 3270 terminal protocol transported over TN3270. */
  TN3270,
  /** Fallback when protocol detection is inconclusive. */
  UNKNOWN
}
