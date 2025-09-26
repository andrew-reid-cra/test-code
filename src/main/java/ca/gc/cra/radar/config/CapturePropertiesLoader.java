package ca.gc.cra.radar.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * <strong>What:</strong> Utility for loading {@link CaptureConfig} from Java {@link Properties} streams.
 * <p><strong>Why:</strong> Allows deployments to externalize capture settings in property files.</p>
 * <p><strong>Role:</strong> Configuration helper used by CLI composition roots.</p>
 * <p><strong>Thread-safety:</strong> Stateless and thread-safe.</p>
 * <p><strong>Performance:</strong> O(n) in the number of properties.</p>
 * <p><strong>Observability:</strong> Callers should log when loading fails.</p>
 *
 * @since 0.1.0
 */
public final class CapturePropertiesLoader {
  private CapturePropertiesLoader() {}

  /**
   * Reads key/value pairs from the supplied stream and builds a {@link CaptureConfig}.
   *
   * @param in properties stream; not closed by this method; must not be {@code null}
   * @return capture configuration derived from the properties
   * @throws IOException if reading the stream fails
   *
   * <p><strong>Concurrency:</strong> Thread-safe; method uses local data structures only.</p>
   * <p><strong>Performance:</strong> O(n) in property entries; delegates to {@link CaptureConfig#fromMap(Map)}.</p>
   * <p><strong>Observability:</strong> Callers should log loading failures or validation errors.</p>
   */
  public static CaptureConfig load(InputStream in) throws IOException {
    Properties props = new Properties();
    props.load(in);
    Map<String, String> kv = new HashMap<>();
    for (String name : props.stringPropertyNames()) {
      kv.put(name, props.getProperty(name));
    }
    return CaptureConfig.fromMap(kv);
  }
}

