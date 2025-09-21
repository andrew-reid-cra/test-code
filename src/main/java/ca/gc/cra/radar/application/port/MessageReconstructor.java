package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.net.ByteStream;
import java.util.List;

/**
 * Port responsible for reconstructing protocol messages from ordered TCP byte streams.
 * <p>Implementations are stateful and generally single-threaded.</p>
 *
 * @since RADAR 0.1-doc
 */
public interface MessageReconstructor {
  /**
   * Prepares the reconstructor for a new session.
   *
   * @since RADAR 0.1-doc
   */
  void onStart();

  /**
   * Consumes a contiguous byte slice and emits zero or more protocol message events.
   *
   * @param slice byte stream slice with flow metadata
   * @return ordered message events extracted from the slice; may be empty
   * @throws Exception if parsing fails
   * @since RADAR 0.1-doc
   */
  List<MessageEvent> onBytes(ByteStream slice) throws Exception;

  /**
   * Releases resources at the end of the session.
   *
   * @since RADAR 0.1-doc
   */
  void onClose();
}
