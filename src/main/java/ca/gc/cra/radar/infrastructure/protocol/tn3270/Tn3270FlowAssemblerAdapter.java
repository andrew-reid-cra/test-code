package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * Flow assembler for TN3270 that forwards non-empty payloads without reordering.
 *
 * @since RADAR 0.1-doc
 */
public final class Tn3270FlowAssemblerAdapter implements FlowAssembler {
  private final MetricsPort metrics;

  /**
   * Creates a TN3270 flow assembler adapter.
   *
   * @param metrics metrics sink used for flow assembler counters
   * @throws NullPointerException if {@code metrics} is {@code null}
   * @since RADAR 0.1-doc
   */
  public Tn3270FlowAssemblerAdapter(MetricsPort metrics) {
    this.metrics = metrics;
  }

  /**
   * Emits a {@link ByteStream} for each non-empty TN3270 payload.
   *
   * @param segment TCP segment to evaluate; may be {@code null}
   * @return byte stream when payload is non-empty; otherwise empty
   * @implNote Emits one {@link ByteStream} per segment without attempting reordering.
   * @since RADAR 0.1-doc
   */
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
