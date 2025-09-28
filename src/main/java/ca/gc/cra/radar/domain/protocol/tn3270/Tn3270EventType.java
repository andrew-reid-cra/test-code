package ca.gc.cra.radar.domain.protocol.tn3270;

/**
 * Event types emitted by the TN3270 assembler.
 *
 * @since RADAR 0.2.0
 */
public enum Tn3270EventType {
  /** Host rendered a new screen buffer. */
  SCREEN_RENDER,
  /** Terminal submitted a user action with modified fields. */
  USER_SUBMIT,
  /** Session negotiation completed and a logical session started. */
  SESSION_START,
  /** Session terminated or timed out. */
  SESSION_END
}
