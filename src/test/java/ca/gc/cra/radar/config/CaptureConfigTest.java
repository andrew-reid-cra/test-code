package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureConfigTest {
  @Test
  void fromMapOverridesDefaults() {
    Map<String, String> inputs = Map.ofEntries(
        Map.entry("iface", "en0"),
        Map.entry("snap", "4096"),
        Map.entry("promisc", "false"),
        Map.entry("timeout", "200"),
        Map.entry("bufmb", "8"),
        Map.entry("immediate", "false"),
        Map.entry("bpf", "tcp port 80"),
        Map.entry("out", "capture/out"),
        Map.entry("fileBase", "capture"),
        Map.entry("rollMiB", "256"),
        Map.entry("httpOut", "capture/http"),
        Map.entry("tnOut", "capture/tn"));
    CaptureConfig cfg = CaptureConfig.fromMap(inputs);

    assertEquals("en0", cfg.iface());
    assertEquals("tcp port 80", cfg.filter());
    assertEquals(4096, cfg.snaplen());
    assertEquals(8 * 1024 * 1024, cfg.bufferBytes());
    assertEquals(200, cfg.timeoutMillis());
    assertEquals(false, cfg.promiscuous());
    assertEquals(false, cfg.immediate());
    assertEquals(Path.of("capture/out"), cfg.outputDirectory());
    assertEquals("capture", cfg.fileBase());
    assertEquals(256, cfg.rollMiB());
    assertEquals(Path.of("capture/http"), cfg.httpOutputDirectory());
    assertEquals(Path.of("capture/tn"), cfg.tn3270OutputDirectory());
  }

  @Test
  void usesDefaultsWhenOptionalFieldsMissing() {
    CaptureConfig cfg = CaptureConfig.fromMap(Map.of("iface", "en1"));

    assertEquals("en1", cfg.iface());
    assertNull(cfg.filter());
    assertEquals(CaptureConfig.defaults().outputDirectory(), cfg.outputDirectory());
    assertEquals(CaptureConfig.defaults().httpOutputDirectory(), cfg.httpOutputDirectory());
    assertEquals(CaptureConfig.defaults().tn3270OutputDirectory(), cfg.tn3270OutputDirectory());
  }

  @Test
  void requiresInterface() {
    assertThrows(IllegalArgumentException.class, () -> CaptureConfig.fromMap(Map.of("iface", "")));
  }
}

