package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Optional;

/**
 * <strong>What:</strong> Domain port that assembles ordered TCP byte streams from captured segments.
 * <p><strong>Why:</strong> Bridges capture (frames and segments) with assemble (protocol reconstruction) stages.</p>
 * <p><strong>Role:</strong> Domain port implemented by adapters such as {@link ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler}.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Maintain per-flow sequencing state.</li>
 *   <li>Emit contiguous byte slices for downstream protocol analyzers.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations usually maintain mutable state per flow; callers must serialize access.</p>
 * <p><strong>Performance:</strong> Should run in O(1) amortized time per segment to remain in the hot path.</p>
 * <p><strong>Observability:</strong> Implementations are expected to surface queue depths and drop counters through provided metrics ports.</p>
 *
 * @implNote Callers assume {@link #accept(TcpSegment)} never returns {@code null} and that returned {@link ByteStream}
 * instances are either immutable or defensively copied.
 * @since 0.1.0
 */
public interface FlowAssembler {
  /**
   * Incorporates a TCP segment into the flow and returns newly contiguous bytes when available.
   *
   * @param segment TCP segment for this flow; must not be {@code null}
   * @return {@link Optional#empty()} when no new bytes are ready; otherwise a {@link ByteStream} owned by the caller
   * @throws Exception if segment processing fails or state cannot be updated
   *
   * <p><strong>Concurrency:</strong> Callers must serialize invocations per flow instance.</p>
   * <p><strong>Performance:</strong> Implementations should avoid extra copies; retransmission handling may introduce O(n) worst cases.</p>
   * <p><strong>Observability:</strong> Implementations typically increment assemble-stage metrics when segments are dropped or reordered.</p>
   */
  Optional<ByteStream> accept(TcpSegment segment) throws Exception;
}
