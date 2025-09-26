package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * <strong>What:</strong> Domain port that classifies flows to protocols using orientation hints and byte signatures.
 * <p><strong>Why:</strong> Enables the assemble stage to select the correct {@link ProtocolModule} before parsing payloads.</p>
 * <p><strong>Role:</strong> Domain port implemented by detection adapters.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Inspect five-tuple metadata and early payload bytes.</li>
 *   <li>Return a {@link ProtocolId} for the flow or {@link ProtocolId#UNKNOWN} when inconclusive.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations should be thread-safe; detectors may be shared across flows.</p>
 * <p><strong>Performance:</strong> Expected to run in O(n) of the supplied preface bytes with minimal allocations.</p>
 * <p><strong>Observability:</strong> Implementations should emit detection counters (e.g., {@code detect.protocol.http}).</p>
 *
 * @since 0.1.0
 * @see ProtocolModule
 */
public interface ProtocolDetector {
  /**
   * Classifies a flow using orientation hints and the captured byte preface.
   *
   * @param flow five-tuple describing the connection; must not be {@code null}
   * @param serverPortHint candidate server port derived from connection orientation
   * @param prefacePeek up to a protocol-specific number of bytes from the start of the stream; may be empty but not {@code null}
   * @return detected protocol id, or {@link ProtocolId#UNKNOWN} when inconclusive
   *
   * <p><strong>Concurrency:</strong> Implementations should be safe for concurrent calls.</p>
   * <p><strong>Performance:</strong> Expected to operate in O(len(prefacePeek)).</p>
   * <p><strong>Observability:</strong> Implementations typically increment detection metrics based on the return value.</p>
   */
  ProtocolId classify(FiveTuple flow, int serverPortHint, byte[] prefacePeek);
}
