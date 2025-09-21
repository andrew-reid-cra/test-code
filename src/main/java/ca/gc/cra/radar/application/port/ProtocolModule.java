package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

/**
 * Pluggable module describing how to detect and reconstruct a specific protocol.
 *
 * @since RADAR 0.1-doc
 */
public interface ProtocolModule {
  /**
   * Returns the protocol identifier served by this module.
   *
   * @return protocol id
   * @since RADAR 0.1-doc
   */
  ProtocolId id();

  /**
   * Provides well-known server ports that hint at this protocol.
   *
   * @return immutable set of default server ports
   * @since RADAR 0.1-doc
   */
  Set<Integer> defaultServerPorts();

  /**
   * Checks whether the given byte preface matches this protocol signature.
   *
   * @param prefacePeek sampled bytes from the beginning of a flow
   * @return {@code true} when the signature matches
   * @since RADAR 0.1-doc
   */
  boolean matchesSignature(byte[] prefacePeek);

  /**
   * Creates a new {@link MessageReconstructor} for the specified flow.
   *
   * @param flow five-tuple associated with the session
   * @param clock clock port used for timestamping
   * @param metrics metrics sink for protocol-specific counters
   * @return initialized reconstructor ready for {@link MessageReconstructor#onStart()}
   * @since RADAR 0.1-doc
   */
  MessageReconstructor newReconstructor(FiveTuple flow, ClockPort clock, MetricsPort metrics);
}
