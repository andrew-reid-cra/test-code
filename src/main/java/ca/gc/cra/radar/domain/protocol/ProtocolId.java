package ca.gc.cra.radar.domain.protocol;

/**
 * Known application protocols handled by RADAR.
 *
 * @since RADAR 0.1-doc
 */
public enum ProtocolId {
  /** Hypertext Transfer Protocol. */
  HTTP,
  /** IBM 3270 terminal protocol transported over TN3270. */
  TN3270,
  /** Fallback when protocol detection is inconclusive. */
  UNKNOWN
}
