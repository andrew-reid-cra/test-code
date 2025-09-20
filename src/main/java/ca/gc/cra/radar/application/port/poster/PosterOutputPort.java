package ca.gc.cra.radar.application.port.poster;

import ca.gc.cra.radar.domain.protocol.ProtocolId;

public interface PosterOutputPort extends AutoCloseable {
  void write(PosterReport report) throws Exception;

  @Override
  default void close() throws Exception {}

  record PosterReport(ProtocolId protocol, String txId, long timestampMicros, String content) {
    public PosterReport {
      if (protocol == null) {
        throw new IllegalArgumentException("protocol must not be null");
      }
      if (txId == null || txId.isBlank()) {
        throw new IllegalArgumentException("txId must not be blank");
      }
      if (content == null) {
        throw new IllegalArgumentException("content must not be null");
      }
    }
  }
}
