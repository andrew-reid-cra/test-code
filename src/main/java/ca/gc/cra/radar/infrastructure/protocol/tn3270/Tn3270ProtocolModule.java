package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.ProtocolModule;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

/**
 * {@link ProtocolModule} implementation for TN3270 traffic.
 *
 * @since RADAR 0.1-doc
 */
public final class Tn3270ProtocolModule implements ProtocolModule {
  /**
   * Creates a protocol module instance.
   *
   * @since RADAR 0.1-doc
   */
  public Tn3270ProtocolModule() {}


  private static final Set<Integer> DEFAULT_PORTS = Set.of(23, 992);

  /**
   * Identifies the TN3270 protocol id.
   *
   * @return {@link ProtocolId#TN3270}
   * @since RADAR 0.1-doc
   */
  @Override
  public ProtocolId id() {
    return ProtocolId.TN3270;
  }

  /**
   * Provides default TN3270 server ports (Telnet and Telnet over TLS).
   *
   * @return immutable set of default ports
   * @since RADAR 0.1-doc
   */
  @Override
  public Set<Integer> defaultServerPorts() {
    return DEFAULT_PORTS;
  }

  /**
   * Checks if the flow&apos;s early bytes resemble TN3270 Telnet negotiation.
   *
   * @param prefacePeek leading bytes from the stream; may be {@code null}
   * @return {@code true} if TN3270 markers are observed
   * @implNote Delegates to {@link Tn3270Signature}.
   * @since RADAR 0.1-doc
   */
  @Override
  public boolean matchesSignature(byte[] prefacePeek) {
    return Tn3270Signature.looksLikeTn3270(prefacePeek);
  }

  /**
   * Creates a new {@link Tn3270MessageReconstructor} for the flow.
   *
   * @param flow five-tuple describing the session
   * @param clock clock port used for timestamps
   * @param metrics metrics sink for TN3270-specific counters
   * @return TN3270 reconstructor
   * @throws NullPointerException if {@code clock} or {@code metrics} is {@code null}
   * @since RADAR 0.1-doc
   */
  @Override
  public MessageReconstructor newReconstructor(
      FiveTuple flow, ClockPort clock, MetricsPort metrics) {
    return new Tn3270MessageReconstructor(clock, metrics);
  }
}
