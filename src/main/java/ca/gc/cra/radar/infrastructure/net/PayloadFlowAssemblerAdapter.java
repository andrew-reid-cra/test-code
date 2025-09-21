package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Objects;
import java.util.Optional;

/**
 * Generic payload assembler that treats each TCP segment payload as an independent byte stream.
 * <p>Infrastructure adapter for {@link FlowAssembler}. Thread-safe if the supplied
 * {@link MetricsPort} is thread-safe.
 *
 * @since RADAR 0.1-doc
 */
public final class PayloadFlowAssemblerAdapter implements FlowAssembler {
  private final MetricsPort metrics;
  private final String metricsPrefix;

  /**
   * Creates a payload assembler adapter.
   *
   * @param metrics metrics sink for recording assembler statistics
   * @param metricsPrefix prefix applied to metric keys; defaults to {@code "flowAssembler"}
   * @throws NullPointerException if {@code metrics} is {@code null}
   * @since RADAR 0.1-doc
   */
  public PayloadFlowAssemblerAdapter(MetricsPort metrics, String metricsPrefix) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.metricsPrefix = (metricsPrefix == null || metricsPrefix.isBlank())
        ? "flowAssembler"
        : metricsPrefix;
  }

  /**
   * Emits a {@link ByteStream} for each non-empty payload while recording metrics.
   *
   * @param segment segment to evaluate; may be {@code null}
   * @return stream slice when payload is non-empty; otherwise empty
   * @implNote Emits one {@link ByteStream} per segment; callers perform higher-level reassembly.
   * @since RADAR 0.1-doc
   */
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

