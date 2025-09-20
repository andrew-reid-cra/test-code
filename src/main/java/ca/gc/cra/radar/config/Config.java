package ca.gc.cra.radar.config;

import ca.gc.cra.radar.domain.protocol.ProtocolId;\nimport java.util.Set;

public record Config(\n    String interfaceName,\n    String bpf,\n    int snapLength,\n    Set<ProtocolId> enabledProtocols) {
  public static Config defaults() {
    return new Config("eth0", "", 65535, Set.of(ProtocolId.HTTP, ProtocolId.TN3270));
  }
}



