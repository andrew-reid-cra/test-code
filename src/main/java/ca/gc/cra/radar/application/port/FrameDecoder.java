package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * Port that decodes captured link-layer frames into TCP segments for higher-level processing.
 *
 * @since RADAR 0.1-doc
 */
public interface FrameDecoder {
  /**
   * Attempts to decode a raw frame into a TCP segment.
   *
   * @param frame raw frame captured from the network; never {@code null}
   * @return decoded segment when recognizable as TCP; otherwise {@link Optional#empty()}
   * @throws Exception if decoding fails irrecoverably (e.g., malformed headers)
   * @since RADAR 0.1-doc
   */
  Optional<TcpSegment> decode(RawFrame frame) throws Exception;
}
