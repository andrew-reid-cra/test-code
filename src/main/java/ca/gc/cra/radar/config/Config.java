package ca.gc.cra.radar.config;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Set;

public record Config(
    String interfaceName,
    String bpf,
    int snapLength,
    Set<ProtocolId> enabledProtocols) {
  public static Config defaults() {
    return new Config("eth0", "", 65535, Set.of(ProtocolId.HTTP, ProtocolId.TN3270));
  }
}
