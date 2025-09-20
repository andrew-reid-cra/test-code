package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Objects;
import java.util.Optional;

/** Generic payload assembler that forwards non-empty TCP payloads as byte streams. */
public final class PayloadFlowAssemblerAdapter implements FlowAssembler {
  private final MetricsPort metrics;
  private final String metricsPrefix;

  public PayloadFlowAssemblerAdapter(MetricsPort metrics, String metricsPrefix) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.metricsPrefix = (metricsPrefix == null || metricsPrefix.isBlank())
        ? "flowAssembler"
        : metricsPrefix;
  }

  @Override
  public Optional<ByteStream> accept(TcpSegment segment) {
    if (segment == null) {
      metrics.increment(metricsPrefix + ".nullSegment");
      return Optional.empty();
    }

    byte[] payload = segment.payload();
    if (payload.length == 0) {
      metrics.increment(metricsPrefix + ".emptyPayload");
      return Optional.empty();
    }

    metrics.increment(metricsPrefix + ".payload");
    metrics.observe(metricsPrefix + ".bytes", payload.length);
    ByteStream slice =
        new ByteStream(
            segment.flow(),
            segment.fromClient(),
            payload,
            segment.timestampMicros());
    return Optional.of(slice);
  }
}
