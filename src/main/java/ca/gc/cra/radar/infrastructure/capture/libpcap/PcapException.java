package ca.gc.cra.radar.infrastructure.capture.libpcap;

/**
 * Checked exception thrown when libpcap operations fail.
 *
 * @since RADAR 0.1-doc
 */
public final class PcapException extends Exception {
  /**
   * Creates an exception with a descriptive message.
   *
   * @param msg human-readable error
   * @since RADAR 0.1-doc
   */
  public PcapException(String msg) { super(msg); }

  /**
   * Creates an exception with a message and underlying cause.
   *
   * @param msg human-readable error
   * @param cause root cause from libpcap or JNI/JNR failures
   * @since RADAR 0.1-doc
   */
  public PcapException(String msg, Throwable cause) { super(msg, cause); }
}
