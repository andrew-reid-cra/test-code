package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

/**
 * <strong>What:</strong> Pluggable module describing how to detect and reconstruct a specific protocol.
 * <p><strong>Why:</strong> Provides the assemble stage with protocol-specific factories without hard-coding implementations.</p>
 * <p><strong>Role:</strong> Domain port representing protocol plugins.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Expose the protocol identifier and well-known ports.</li>
 *   <li>Validate payload signatures.</li>
 *   <li>Construct {@link MessageReconstructor} instances for flows.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations are typically stateless and thread-safe.</p>
 * <p><strong>Performance:</strong> Signature checks should be O(n) in preface length; reconstructor creation should allocate minimal state.</p>
 * <p><strong>Observability:</strong> Modules should tag metrics with {@link ProtocolId} and emit detection counters.</p>
 *
 * @since 0.1.0
 * @see ProtocolDetector
 */
public interface ProtocolModule {
  /**
   * Returns the protocol identifier served by this module.
   *
   * @return protocol id; never {@code null}
   *
   * <p><strong>Concurrency:</strong> Safe to call concurrently.</p>
   * <p><strong>Performance:</strong> Constant time.</p>
   * <p><strong>Observability:</strong> Callers log the selected protocol for debugging.</p>
   */
  ProtocolId id();

  /**
   * Provides well-known server ports that hint at this protocol.
   *
   * @return immutable set of default server ports; may be empty when port hints are not reliable
   *
   * <p><strong>Concurrency:</strong> Thread-safe.</p>
   * <p><strong>Performance:</strong> Constant time view.</p>
   * <p><strong>Observability:</strong> Values typically become detection heuristics recorded in metrics.</p>
   */
  Set<Integer> defaultServerPorts();

  /**
   * Checks whether the given byte preface matches this protocol signature.
   *
   * @param prefacePeek sampled bytes from the beginning of a flow; must not be {@code null}
   * @return {@code true} when the signature matches
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent invocation.</p>
   * <p><strong>Performance:</strong> Expected O(len(prefacePeek)).</p>
   * <p><strong>Observability:</strong> Implementations should increment signature-match metrics.</p>
   */
  boolean matchesSignature(byte[] prefacePeek);

  /**
   * Creates a new {@link MessageReconstructor} for the specified flow.
   *
   * @param flow five-tuple associated with the session; must not be {@code null}
   * @param clock clock port used for timestamping; must not be {@code null}
   * @param metrics metrics sink for protocol-specific counters; must not be {@code null}
   * @return initialized reconstructor ready for {@link MessageReconstructor#onStart()}
   *
   * <p><strong>Concurrency:</strong> Call per flow; returned reconstructor is usually single-threaded.</p>
   * <p><strong>Performance:</strong> Should allocate minimal state and avoid heavy initialization.</p>
   * <p><strong>Observability:</strong> Implementation may emit module creation metrics (e.g., {@code assemble.reconstructor.created}).</p>
   */
  MessageReconstructor newReconstructor(FiveTuple flow, ClockPort clock, MetricsPort metrics);
}
