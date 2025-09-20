package ca.gc.cra.radar.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class CapturePropertiesLoader {
  private CapturePropertiesLoader() {}

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
