package ca.gc.cra.radar.config;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

public final class ConfigLoader {
  private ConfigLoader() {}

  public static Config fromProperties(Path path) throws IOException {
    Properties props = new Properties();
    if (path != null && Files.exists(path)) {
      try (var reader = Files.newBufferedReader(path)) {
        props.load(reader);
      }
    }
    String iface = props.getProperty("iface", Config.defaults().interfaceName());
    String bpf = props.getProperty("bpf", Config.defaults().bpf());
    int snap = Integer.parseInt(props.getProperty("snap", String.valueOf(Config.defaults().snapLength())));
    Set<ProtocolId> enabled = EnumSet.copyOf(Config.defaults().enabledProtocols());
    String protocols = props.getProperty("protocols");
    if (protocols != null && !protocols.isBlank()) {
      enabled.clear();
      for (String token : protocols.split(",")) {
        enabled.add(ProtocolId.valueOf(token.trim().toUpperCase()));
      }
    }
    return new Config(iface, bpf, snap, enabled);
  }
}


