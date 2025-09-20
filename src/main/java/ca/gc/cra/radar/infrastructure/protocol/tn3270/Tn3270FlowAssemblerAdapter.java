package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

public final class Tn3270FlowAssemblerAdapter implements FlowAssembler {
  private final MetricsPort metrics;

  public Tn3270FlowAssemblerAdapter(MetricsPort metrics) {
    this.metrics = metrics;
  }

  @Override
  public Optional<ByteStream> accept(TcpSegment segment) {
    if (segment == null) {
      metrics.increment("tn3270.flowAssembler.nullSegment");
      return Optional.empty();
    }

    byte[] payload = segment.payload();
    if (payload.length == 0) {
      metrics.increment("tn3270.flowAssembler.emptyPayload");
      return Optional.empty();
    }

    metrics.increment("tn3270.flowAssembler.payload");
    metrics.observe("tn3270.flowAssembler.bytes", payload.length);
    ByteStream slice =
        new ByteStream(
            segment.flow(),
            segment.fromClient(),
            payload,
            segment.timestampMicros());
    return Optional.of(slice);
  }
}
