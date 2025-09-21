package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * Detects the application protocol for a flow based on early payload and port hints.
 *
 * @since RADAR 0.1-doc
 */
public interface ProtocolDetector {
  /**
   * Classifies a flow using orientation hints and the captured byte preface.
   *
   * @param flow five-tuple describing the connection
   * @param serverPortHint candidate server port derived from connection orientation
   * @param prefacePeek up to a protocol-specific number of bytes from the start of the stream
   * @return detected protocol id, or {@link ProtocolId#UNKNOWN} when inconclusive
   * @since RADAR 0.1-doc
   */
  ProtocolId classify(FiveTuple flow, int serverPortHint, byte[] prefacePeek);
}
