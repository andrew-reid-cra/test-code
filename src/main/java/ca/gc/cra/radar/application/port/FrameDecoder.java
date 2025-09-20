package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

public interface FrameDecoder {
  Optional<TcpSegment> decode(RawFrame frame) throws Exception;
}


