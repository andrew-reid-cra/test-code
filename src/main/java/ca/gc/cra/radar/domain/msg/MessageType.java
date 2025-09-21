package ca.gc.cra.radar.domain.msg;

/**
 * Direction of a reconstructed protocol message.
 *
 * @since RADAR 0.1-doc
 */
public enum MessageType {
  /** Client-to-server message. */
  REQUEST,
  /** Server-to-client message. */
  RESPONSE
}
