package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.context.FlowContext;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

public interface ContextualFlowAssembler extends FlowAssembler {
  Optional<ByteStream> accept(TcpSegment segment, FlowContext context) throws Exception;
}


