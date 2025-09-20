package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

public interface FlowAssembler {
  Optional<ByteStream> accept(TcpSegment segment) throws Exception;
}


