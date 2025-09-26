package ca.gc.cra.radar.config;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * <strong>What:</strong> Loads {@link Config} instances from configuration files.
 * <p><strong>Why:</strong> Allows operators to override default capture settings via property files.</p>
 * <p><strong>Role:</strong> Configuration helper used by CLI composition roots.</p>
 * <p><strong>Thread-safety:</strong> Stateless and thread-safe.</p>
 * <p><strong>Performance:</strong> O(n) in the number of properties.</p>
 * <p><strong>Observability:</strong> Callers should log when configuration files are missing or malformed.</p>
 *
 * @since 0.1.0
 */
public final class ConfigLoader {
  private ConfigLoader() {}

  /**
   * Reads optional configuration properties from the given path and builds a {@link Config}.
   *
   * @param path properties file path; may be {@code null} or non-existent to use defaults
   * @return configuration populated with file values overriding defaults
   * @throws IOException if the file exists but cannot be read
   *
   * <p><strong>Concurrency:</strong> Thread-safe; method allocates local property objects.</p>
   * <p><strong>Performance:</strong> O(n) in property entries; defaults fill missing values.</p>
   * <p><strong>Observability:</strong> Callers should log path resolution and catch parsing errors for operators.</p>
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

