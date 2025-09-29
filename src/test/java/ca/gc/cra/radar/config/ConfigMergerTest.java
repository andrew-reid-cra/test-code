package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigMergerTest {

  @Test
  void cliOverridesYamlAndEmitsWarning() {
    Map<String, String> defaults = Map.of("iface", "eth0", "metricsExporter", "otlp");
    Map<String, String> yaml = Map.of("iface", "en0", "bufmb", "256");
    Map<String, String> cli = Map.of("iface", "en1", "bufmb", "512");
    List<String> warnings = new ArrayList<>();

    Map<String, String> merged = ConfigMerger.buildEffectiveConfig(
        "capture",
        Optional.of(yaml),
        cli,
        defaults,
        warnings::add);

    assertEquals("en1", merged.get("iface"));
    assertEquals("512", merged.get("bufmb"));
    assertEquals(2, warnings.size());
    assertTrue(warnings.contains("CLI overrides YAML for key: iface"));
    assertTrue(warnings.contains("CLI overrides YAML for key: bufmb"));
  }

  @Test
  void kafkaModeRequiresBootstrap() {
    Map<String, String> defaults = Map.of("ioMode", "KAFKA");
    Map<String, String> cli = Map.of("ioMode", "KAFKA");

    assertThrows(
        IllegalArgumentException.class,
        () -> ConfigMerger.buildEffectiveConfig(
            "capture",
            Optional.empty(),
            cli,
            defaults,
            msg -> {}));
  }

  @Test
  void assembleRequiresAtLeastOneProtocol() {
    Map<String, String> defaults = new LinkedHashMap<>();
    defaults.put("httpEnabled", "false");
    defaults.put("tnEnabled", "false");

    assertThrows(
        IllegalArgumentException.class,
        () -> ConfigMerger.buildEffectiveConfig(
            "assemble",
            Optional.of(Map.of()),
            Map.of(),
            defaults,
            msg -> {}));
  }

  @Test
  void bpfRequiresEnableFlag() {
    Map<String, String> defaults = Map.of("bpf", "tcp", "enableBpf", "false");

    assertThrows(
        IllegalArgumentException.class,
        () -> ConfigMerger.buildEffectiveConfig(
            "capture",
            Optional.empty(),
            Map.of(),
            defaults,
            msg -> {}));
  }
}
