package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.ProtocolModule;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

public final class HttpProtocolModule implements ProtocolModule {
  private static final Set<Integer> DEFAULT_PORTS = Set.of(80, 8080, 8000, 3128);

  @Override
  public ProtocolId id() {
    return ProtocolId.HTTP;
  }

  @Override
  public Set<Integer> defaultServerPorts() {
    return DEFAULT_PORTS;
  }

  @Override
  public boolean matchesSignature(byte[] prefacePeek) {
    return HttpSignature.looksLikeHttp(prefacePeek);
  }

  @Override
  public MessageReconstructor newReconstructor(
      FiveTuple flow, ClockPort clock, MetricsPort metrics) {
    return new HttpMessageReconstructor(clock, metrics);
  }
}


