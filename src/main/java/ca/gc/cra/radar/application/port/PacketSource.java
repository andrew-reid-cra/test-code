package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.net.RawFrame;
import java.util.Optional;

public interface PacketSource extends AutoCloseable {
  void start() throws Exception;

  Optional<RawFrame> poll() throws Exception;

  @Override
  void close() throws Exception;
}


