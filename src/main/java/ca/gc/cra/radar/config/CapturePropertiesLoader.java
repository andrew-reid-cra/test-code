package ca.gc.cra.radar.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Utility for loading {@link CaptureConfig} from Java {@link Properties} streams.
 * <p>Stateless and thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class CapturePropertiesLoader {
  private CapturePropertiesLoader() {}

  /**
   * Reads key/value pairs from the supplied stream and builds a {@link CaptureConfig}.
   *
   * @param in properties stream; not closed by this method
   * @return capture configuration derived from the properties
   * @throws IOException if reading the stream fails
   * @since RADAR 0.1-doc
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
