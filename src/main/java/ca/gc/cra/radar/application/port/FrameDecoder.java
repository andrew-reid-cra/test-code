package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * <strong>What:</strong> Domain port that converts captured link-layer frames into TCP segments.
 * <p><strong>Why:</strong> Separates frame parsing from flow assembly so adapters can swap decoding implementations.</p>
 * <p><strong>Role:</strong> Domain port implemented by capture adapters (e.g., {@link ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap}).</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Recognize TCP payloads inside Ethernet/IPv4/IPv6 frames.</li>
 *   <li>Provide rich error reporting for malformed frames.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations should be stateless or externally synchronized.</p>
 * <p><strong>Performance:</strong> Hot-path operation; should reuse buffers to minimize allocations.</p>
 * <p><strong>Observability:</strong> Expected to emit metrics for malformed frames and protocol coverage.</p>
 *
 * @implNote Callers assume {@link #decode(RawFrame)} never returns {@code null}.
 * @since 0.1.0
 */
public interface FrameDecoder {
  /**
   * Attempts to decode a raw frame into a TCP segment.
   *
   * @param frame raw frame captured from the network; must not be {@code null}
   * @return decoded segment when recognizable as TCP; otherwise {@link Optional#empty()}
   * @throws Exception if decoding fails irrecoverably (e.g., malformed headers)
   *
   * <p><strong>Concurrency:</strong> Safe when implementation is stateless; otherwise guard per instance.</p>
   * <p><strong>Performance:</strong> Expected O(1) header parsing; heavy copies should be avoided.</p>
   * <p><strong>Observability:</strong> Implementations typically increment capture-stage metrics for drops and parse failures.</p>
   */
  Optional<TcpSegment> decode(RawFrame frame) throws Exception;
}



