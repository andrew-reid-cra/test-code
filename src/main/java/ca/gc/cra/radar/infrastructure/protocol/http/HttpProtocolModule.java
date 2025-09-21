package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.ProtocolModule;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

/**
 * {@link ProtocolModule} implementation for HTTP traffic.
 *
 * @since RADAR 0.1-doc
 */
public final class HttpProtocolModule implements ProtocolModule {
  /**
   * Creates a protocol module instance.
   *
   * @since RADAR 0.1-doc
   */
  public HttpProtocolModule() {}


  private static final Set<Integer> DEFAULT_PORTS = Set.of(80, 8080, 8000, 3128);

  /**
   * Identifies the HTTP protocol id.
   *
   * @return {@link ProtocolId#HTTP}
   * @since RADAR 0.1-doc
   */
  @Override
  public ProtocolId id() {
    return ProtocolId.HTTP;
  }

  /**
   * Provides common HTTP server ports for coarse detection hints.
   *
   * @return immutable set of default HTTP ports
   * @since RADAR 0.1-doc
   */
  @Override
  public Set<Integer> defaultServerPorts() {
    return DEFAULT_PORTS;
  }

  /**
   * Checks whether the captured bytes resemble an HTTP request/response preface.
   *
   * @param prefacePeek leading bytes from the stream; may be {@code null}
   * @return {@code true} if the signature looks like HTTP
   * @implNote Uses lightweight heuristics via {@link HttpSignature}.
   * @since RADAR 0.1-doc
   */
  @Override
  public boolean matchesSignature(byte[] prefacePeek) {
    return HttpSignature.looksLikeHttp(prefacePeek);
  }

  /**
   * Creates a new {@link HttpMessageReconstructor} for the flow.
   *
   * @param flow five-tuple for the session
   * @param clock clock used for timestamping
   * @param metrics metrics sink for HTTP-specific counters
   * @return new HTTP message reconstructor
   * @throws NullPointerException if {@code clock} or {@code metrics} is {@code null}
   * @since RADAR 0.1-doc
   */
  @Override
  public MessageReconstructor newReconstructor(
      FiveTuple flow, ClockPort clock, MetricsPort metrics) {
    return new HttpMessageReconstructor(clock, metrics);
  }
}
