package ca.gc.cra.radar.domain.msg;

/**
 * <strong>What:</strong> Direction of a reconstructed protocol message.
 * <p><strong>Why:</strong> Guides pairing engines and sinks when interpreting {@link MessageEvent}s.</p>
 * <p><strong>Role:</strong> Domain enumeration used across assemble and poster stages.</p>
 * <p><strong>Thread-safety:</strong> Enum constants are immutable and globally shareable.</p>
 * <p><strong>Performance:</strong> Constant-time comparisons.</p>
 * <p><strong>Observability:</strong> Used as a tag value in metrics such as {@code assemble.message.count}.</p>
 *
 * @since 0.1.0
 */
public enum MessageType {
  /** Client-to-server message. */
  REQUEST,
  /** Server-to-client message. */
  RESPONSE
}
