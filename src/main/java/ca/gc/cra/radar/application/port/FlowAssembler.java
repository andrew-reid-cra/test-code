package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * Port that aggregates TCP segments for a single flow into ordered byte streams.
 * <p>Implementations may retain state per flow and are generally not thread-safe without external
 * synchronization.</p>
 *
 * @since RADAR 0.1-doc
 */
public interface FlowAssembler {
  /**
   * Accepts a TCP segment and, when new contiguous bytes become available, returns the slice as a
   * {@link ByteStream}.
   *
   * @param segment TCP segment belonging to the flow managed by this assembler
   * @return non-empty stream slice when new bytes are ready; otherwise {@link Optional#empty()}
   * @throws Exception if segment processing fails
   * @since RADAR 0.1-doc
   */
  Optional<ByteStream> accept(TcpSegment segment) throws Exception;
}
