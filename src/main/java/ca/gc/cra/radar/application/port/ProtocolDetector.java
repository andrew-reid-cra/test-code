package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

public interface ProtocolDetector {
  ProtocolId classify(FiveTuple flow, int serverPortHint, byte[] prefacePeek);
}


