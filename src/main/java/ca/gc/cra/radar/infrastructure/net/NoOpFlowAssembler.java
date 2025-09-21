package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * Flow assembler implementation that never emits data. Useful for disabling flow assembly while
 * still satisfying the {@link FlowAssembler} contract.
 *
 * @since RADAR 0.1-doc
 */
public final class NoOpFlowAssembler implements FlowAssembler {
  /**
   * Creates a no-op flow assembler.
   *
   * @since RADAR 0.1-doc
   */
  public NoOpFlowAssembler() {}

  /**
   * Returns {@link Optional#empty()} for every segment.
   *
   * @param segment segment ignored by this assembler; may be {@code null}
   * @return always {@link Optional#empty()}
   * @implNote Does not inspect the segment to avoid additional allocations.
   * @since RADAR 0.1-doc
   */
  @Override
  public Optional<ByteStream> accept(TcpSegment segment) {
    return Optional.empty();
  }
}

