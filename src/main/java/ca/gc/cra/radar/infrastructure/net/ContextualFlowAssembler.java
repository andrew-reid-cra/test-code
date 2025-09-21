package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.context.FlowContext;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * Flow assembler extension that receives explicit flow context with each segment.
 *
 * @since RADAR 0.1-doc
 */
public interface ContextualFlowAssembler extends FlowAssembler {
  /**
   * Accepts a TCP segment plus flow context and optionally emits a contiguous byte stream.
   *
   * @param segment TCP segment
   * @param context flow orientation context derived by the application layer
   * @return byte stream slice when new data becomes readable; otherwise empty
   * @throws Exception if assembly fails
   * @since RADAR 0.1-doc
   */
  Optional<ByteStream> accept(TcpSegment segment, FlowContext context) throws Exception;
}
