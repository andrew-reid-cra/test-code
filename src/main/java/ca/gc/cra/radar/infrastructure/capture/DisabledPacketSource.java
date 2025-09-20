package ca.gc.cra.radar.infrastructure.capture;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import java.util.Optional;

/**
 * Placeholder packet source used until the live CLI wires the real capture adapter.
 * Starting it throws to signal incomplete wiring at runtime.
 */
public final class DisabledPacketSource implements PacketSource {
  @Override
  public void start() {
    throw new UnsupportedOperationException("PacketSource not configured");
  }

  @Override
  public Optional<RawFrame> poll() {
    return Optional.empty();
  }

  @Override
  public void close() {}
}
