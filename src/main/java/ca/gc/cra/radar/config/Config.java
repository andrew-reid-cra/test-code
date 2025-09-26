package ca.gc.cra.radar.config;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

/**
 * <strong>What:</strong> Immutable runtime configuration shared across RADAR CLIs.
 * <p><strong>Why:</strong> Provides default capture hints and protocol enablement when external config is absent.</p>
 * <p><strong>Role:</strong> Configuration record consumed by CLI composition roots.</p>
 * <p><strong>Thread-safety:</strong> Record is immutable; safe for concurrent reads.</p>
 * <p><strong>Performance:</strong> Holds simple value types; suitable for frequent access.</p>
 * <p><strong>Observability:</strong> Fields surface in logs and metrics describing capture defaults.</p>
 *
 * @param interfaceName network interface used for capture defaults (e.g., {@code eth0})
 * @param bpf default Berkeley Packet Filter expression; empty string disables filtering
 * @param snapLength default snap length for packet capture in bytes
 * @param enabledProtocols protocols enabled by default for reconstruction
 * @since 0.1.0
 */
public record Config(
    String interfaceName,
    String bpf,
    int snapLength,
    Set<ProtocolId> enabledProtocols) {

  /**
   * Provides default configuration values used when no external config is supplied.
   *
   * @return default configuration record
   *
   * <p><strong>Concurrency:</strong> Thread-safe static factory.</p>
   * <p><strong>Performance:</strong> Constant-time record instantiation.</p>
   * <p><strong>Observability:</strong> Defaults inform CLI logging when config files are absent.</p>
   */
  public static Config defaults() {
    return new Config("eth0", "tcp", 65535, Set.of(ProtocolId.HTTP, ProtocolId.TN3270));
  }
}
