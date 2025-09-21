package ca.gc.cra.radar.config;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * Loads {@link Config} instances from configuration files.
 * <p>Thread-safe; performs a best-effort parse with sensible defaults.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class ConfigLoader {
  private ConfigLoader() {}

  /**
   * Reads optional configuration properties from the given path and builds a {@link Config}.
   *
   * @param path properties file path; may be {@code null} or non-existent to use defaults
   * @return configuration populated with file values overriding defaults
   * @throws IOException if the file exists but cannot be read
   * @since RADAR 0.1-doc
   */
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
