package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

public interface ProtocolModule {
  ProtocolId id();

  Set<Integer> defaultServerPorts();

  boolean matchesSignature(byte[] prefacePeek);

  MessageReconstructor newReconstructor(FiveTuple flow, ClockPort clock, MetricsPort metrics);
}


