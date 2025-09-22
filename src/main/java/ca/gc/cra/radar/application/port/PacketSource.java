package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.RawFrame;
import java.util.Optional;

/**
 * Port that provides captured raw frames from a network interface or trace.
 * <p>Implementations typically wrap libpcap or replay files. Not inherently thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
public interface PacketSource extends AutoCloseable {
  /**
   * Starts capture or prepares the underlying source.
   *
   * @throws Exception if the source cannot be opened
   * @since RADAR 0.1-doc
   */
  void start() throws Exception;

  /**
   * Retrieves the next frame when available.
   *
   * @return optional frame; empty when no data is currently available or the source is exhausted
   * @throws Exception if capture fails
   * @since RADAR 0.1-doc
   */
  Optional<RawFrame> poll() throws Exception;

  /**
   * Indicates whether the source has been fully drained and will not deliver more frames.
   *
   * @return {@code true} when the source is exhausted and capture can shut down
   * @since RADAR 0.1-doc
   */
  default boolean isExhausted() {
    return false;
  }

  /**
   * Closes the underlying capture resources.
   *
   * @throws Exception if shutdown fails
   * @since RADAR 0.1-doc
   */
  @Override
  void close() throws Exception;
}
