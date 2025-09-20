package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

public final class NoOpFlowAssembler implements FlowAssembler {
  @Override
  public Optional<ByteStream> accept(TcpSegment segment) {
    return Optional.empty();
  }
}


