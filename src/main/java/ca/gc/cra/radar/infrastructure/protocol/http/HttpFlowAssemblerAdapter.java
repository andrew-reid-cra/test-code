package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * Basic HTTP flow assembler that forwards non-empty TCP payloads as ByteStream slices, preserving
 * direction and timestamps. Future iterations can enhance this to perform true reassembly.
 */
public final class HttpFlowAssemblerAdapter implements FlowAssembler {
  private final MetricsPort metrics;

  public HttpFlowAssemblerAdapter(MetricsPort metrics) {
    this.metrics = metrics;
  }

  @Override
  public Optional<ByteStream> accept(TcpSegment segment) {
    if (segment == null) {
      metrics.increment("http.flowAssembler.nullSegment");
      return Optional.empty();
    }

    byte[] payload = segment.payload();
    if (payload.length == 0) {
      metrics.increment("http.flowAssembler.emptyPayload");
      return Optional.empty();
    }

    metrics.increment("http.flowAssembler.payload");
    metrics.observe("http.flowAssembler.bytes", payload.length);
    ByteStream slice =
        new ByteStream(
            segment.flow(),
            segment.fromClient(),
            payload,
            segment.timestampMicros());
    return Optional.of(slice);
  }
}
