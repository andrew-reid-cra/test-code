package ca.gc.cra.radar.infrastructure.capture;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import java.util.Optional;

/**
 * Placeholder packet source used until the live CLI wires the real capture adapter.
 * Starting it throws to signal incomplete wiring at runtime.
 */
public final class DisabledPacketSource implements PacketSource {
  /**
   * Creates a disabled packet source placeholder.
   *
   * @since RADAR 0.1-doc
   */
  public DisabledPacketSource() {}

  /**
   * No-op start method for the disabled source.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public void start() {
    throw new UnsupportedOperationException("PacketSource not configured");
  }

  /**
   * Always returns {@link Optional#empty()}.
   *
   * @return always empty
   * @since RADAR 0.1-doc
   */
  @Override
  public Optional<RawFrame> poll() {
    return Optional.empty();
  }

  /**
   * No-op close hook retained for interface parity; this adapter never allocates resources.
   * <p>The method is idempotent and safe to invoke multiple times.</p>
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() {}
}

