package ca.gc.cra.radar.config;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

/**
 * Immutable runtime configuration shared across RADAR CLIs.
 *
 * @param interfaceName network interface used for capture defaults
 * @param bpf default Berkeley Packet Filter expression; empty string to disable
 * @param snapLength default snap length for packet capture
 * @param enabledProtocols protocols enabled by default for reconstruction
 * @since RADAR 0.1-doc
 */
public record Config(
    String interfaceName,
    String bpf,
    int snapLength,
    Set<ProtocolId> enabledProtocols) {
  /**
   * Provides default configuration values used when no external config is supplied.
   *
   * @return default configuration
   * @since RADAR 0.1-doc
   */
  public static Config defaults() {
    return new Config("eth0", "", 65535, Set.of(ProtocolId.HTTP, ProtocolId.TN3270));
  }
}
