package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.RawFrame;
import java.util.Optional;

/**
 * <strong>What:</strong> Domain port that supplies captured frames to the RADAR pipeline.
 * <p><strong>Why:</strong> Abstracts live capture and offline replay sources so pipelines stay agnostic to transport mechanics.</p>
 * <p><strong>Role:</strong> Domain port implemented by adapters such as {@code PcapPacketSource} and {@code PcapFilePacketSource}.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Open and close capture resources safely.</li>
 *   <li>Poll for new frames with bounded blocking semantics.</li>
 *   <li>Signal exhaustion so downstream stages can shut down.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations typically expect single-threaded polling; external synchronization required otherwise.</p>
 * <p><strong>Performance:</strong> Calls occur on the hot capture path; adapters should minimize copies and JNI transitions.</p>
 * <p><strong>Observability:</strong> Implementations emit capture metrics (e.g., {@code capture.frames.total}, {@code capture.frames.error}).</p>
 *
 * @implNote Callers must invoke {@link #start()} before polling and always call {@link #close()} to release native handles.
 * @since 0.1.0
 */
public interface PacketSource extends AutoCloseable {
  /**
   * Starts capture or prepares the underlying source.
   *
   * @throws Exception if the source cannot be opened or configured
   *
   * <p><strong>Concurrency:</strong> Called once prior to polling.</p>
   * <p><strong>Performance:</strong> May perform blocking IO during initialization.</p>
   * <p><strong>Observability:</strong> Implementations should emit startup metrics/logs (e.g., device name, BPF filters).</p>
   */
  void start() throws Exception;

  /**
   * Retrieves the next frame when available.
   *
   * @return {@link Optional} containing a frame; empty when no data is currently available or the source is exhausted
   * @throws Exception if capture fails or the underlying device errors
   *
   * <p><strong>Concurrency:</strong> Callers poll from a single capture thread unless documented otherwise.</p>
   * <p><strong>Performance:</strong> Expected to block for at most the device timeout; copies should be minimized.</p>
   * <p><strong>Observability:</strong> Implementations typically increment metrics and attach timestamps.</p>
   */
  Optional<RawFrame> poll() throws Exception;

  /**
   * Indicates whether the source has been fully drained and will not deliver more frames.
   *
   * @return {@code true} when the source is exhausted and capture can shut down
   *
   * <p><strong>Concurrency:</strong> Safe for capture and control-plane threads.</p>
   * <p><strong>Performance:</strong> Constant time.</p>
   * <p><strong>Observability:</strong> Implementations may map this to {@code capture.source.exhausted} metrics.</p>
   */
  default boolean isExhausted() {
    return false;
  }

  /**
   * Closes the underlying capture resources.
   *
   * @throws Exception if shutdown fails or native resources cannot be released
   *
   * <p><strong>Concurrency:</strong> Should only be called once capture has stopped polling.</p>
   * <p><strong>Performance:</strong> May block while waiting for device teardown.</p>
   * <p><strong>Observability:</strong> Implementations usually log shutdown and emit final counters.</p>
   */
  @Override
  void close() throws Exception;
}

