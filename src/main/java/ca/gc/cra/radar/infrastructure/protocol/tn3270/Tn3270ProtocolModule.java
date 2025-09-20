package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.ProtocolModule;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

public final class Tn3270ProtocolModule implements ProtocolModule {
  private static final Set<Integer> DEFAULT_PORTS = Set.of(23, 992);

  @Override
  public ProtocolId id() {
    return ProtocolId.TN3270;
  }

  @Override
  public Set<Integer> defaultServerPorts() {
    return DEFAULT_PORTS;
  }

  @Override
  public boolean matchesSignature(byte[] prefacePeek) {
    return Tn3270Signature.looksLikeTn3270(prefacePeek);
  }

  @Override
  public MessageReconstructor newReconstructor(
      FiveTuple flow, ClockPort clock, MetricsPort metrics) {
    return new Tn3270MessageReconstructor(clock, metrics);
  }
}


