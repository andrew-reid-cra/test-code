package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureConfigTest {
  @TempDir Path tempDir;
  private String originalHome;

  @BeforeEach
  void setUpUserHome() {
    originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
  }

  @AfterEach
  void restoreUserHome() {
    if (originalHome == null) {
      System.clearProperty("user.home");
    } else {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void fromMapAppliesOverridesWithEnabledBpf() {
    Path root = tempDir.resolve("capture-root");
    Map<String, String> inputs = Map.ofEntries(
        Map.entry("iface", "en0"),
        Map.entry("snap", "4096"),
        Map.entry("promisc", "true"),
        Map.entry("timeout", "200"),
        Map.entry("bufmb", "8"),
        Map.entry("immediate", "true"),
        Map.entry("bpf", "tcp port 80"),
        Map.entry("enableBpf", "true"),
        Map.entry("out", root.resolve("segments").toString()),
        Map.entry("fileBase", "capture"),
        Map.entry("rollMiB", "256"),
        Map.entry("httpOut", root.resolve("http").toString()),
        Map.entry("tnOut", root.resolve("tn").toString()),
        Map.entry("tn3270.emitScreenRenders", "true"),
        Map.entry("tn3270.screenRenderSampleRate", "0.5"),
        Map.entry("tn3270.redaction.policy", "(?i)^(SIN|DOB)$"),
        Map.entry("persistWorkers", "2"),
        Map.entry("persistQueueCapacity", "256"));

    CaptureConfig cfg = CaptureConfig.fromMap(inputs);

    assertEquals("en0", cfg.iface());
    assertNull(cfg.pcapFile());
    assertEquals("tcp port 80", cfg.filter());
    assertEquals(CaptureProtocol.GENERIC, cfg.protocol());
    assertTrue(cfg.customBpfEnabled());
    assertEquals(4096, cfg.snaplen());
    assertEquals(8 * 1024 * 1024, cfg.bufferBytes());
    assertEquals(200, cfg.timeoutMillis());
    assertTrue(cfg.promiscuous());
    assertTrue(cfg.immediate());
    assertTrue(cfg.outputDirectory().isAbsolute());
    assertTrue(cfg.outputDirectory().endsWith(Path.of("capture-root", "segments")));
    assertEquals("capture", cfg.fileBase());
    assertEquals(256, cfg.rollMiB());
    assertTrue(cfg.httpOutputDirectory().endsWith(Path.of("capture-root", "http")));
    assertTrue(cfg.tn3270OutputDirectory().endsWith(Path.of("capture-root", "tn")));
    assertEquals(2, cfg.persistenceWorkers());
    assertEquals(256, cfg.persistenceQueueCapacity());
    assertTrue(cfg.tn3270EmitScreenRenders());
    assertEquals(0.5d, cfg.tn3270ScreenRenderSampleRate());
    assertEquals("(?i)^(SIN|DOB)$", cfg.tn3270RedactionPolicy());
  }

  @Test
  void defaultsAreSafeWhenOnlyInterfaceProvided() {
    CaptureConfig cfg = CaptureConfig.fromMap(Map.of("iface", "en1"));

    assertEquals("en1", cfg.iface());
    assertNull(cfg.pcapFile());
    assertEquals("tcp", cfg.filter());
    assertEquals(CaptureProtocol.GENERIC, cfg.protocol());
    assertFalse(cfg.customBpfEnabled());
    CaptureConfig defaults = CaptureConfig.defaults();
    assertEquals(defaults.outputDirectory(), cfg.outputDirectory());
    assertEquals(defaults.httpOutputDirectory(), cfg.httpOutputDirectory());
    assertEquals(defaults.tn3270OutputDirectory(), cfg.tn3270OutputDirectory());
    assertEquals(defaults.snaplen(), cfg.snaplen());
    assertEquals(defaults.tn3270EmitScreenRenders(), cfg.tn3270EmitScreenRenders());
    assertEquals(defaults.tn3270ScreenRenderSampleRate(), cfg.tn3270ScreenRenderSampleRate());
    assertEquals(defaults.tn3270RedactionPolicy(), cfg.tn3270RedactionPolicy());
    assertEquals(defaults.bufferBytes(), cfg.bufferBytes());
  }

  @Test
  void acceptsOfflinePcapFileWithoutInterface() throws IOException {
    Path pcap = tempDir.resolve("trace.pcap");
    Files.createFile(pcap);

    CaptureConfig cfg = CaptureConfig.fromMap(Map.of("pcapFile", pcap.toString()));

    assertEquals(pcap.toAbsolutePath().normalize(), cfg.pcapFile());
    assertTrue(cfg.iface().startsWith("pcap:"));
    assertEquals("tcp", cfg.filter());
    assertEquals(CaptureProtocol.GENERIC, cfg.protocol());
  }

  @Test
  void snaplenOverridesSnapWhenBothProvided() {
    Map<String, String> inputs = Map.of(
        "iface", "en0",
        "snap", "1024",
        "snaplen", "2048");

    CaptureConfig cfg = CaptureConfig.fromMap(inputs);

    assertEquals(2048, cfg.snaplen());
  }

  @Test
  void tn3270ProtocolUsesDefaultFilter() {
    CaptureConfig cfg = CaptureConfig.fromMap(Map.of("iface", "en2", "protocol", "TN3270"));

    assertEquals("tcp and (port 23 or port 992)", cfg.filter());
    assertEquals(CaptureProtocol.TN3270, cfg.protocol());
    assertFalse(cfg.customBpfEnabled());
  }

  @Test
  void customBpfOverridesProtocolDefaults() {
    CaptureConfig cfg = CaptureConfig.fromMap(Map.ofEntries(
        Map.entry("iface", "en3"),
        Map.entry("protocol", "TN3270"),
        Map.entry("enableBpf", "true"),
        Map.entry("bpf", "tcp port 992")));

    assertEquals("tcp port 992", cfg.filter());
    assertEquals(CaptureProtocol.TN3270, cfg.protocol());
    assertTrue(cfg.customBpfEnabled());
  }


  @Test
  void protocolDefaultFilterOverrideAppliesWithoutEnableBpf() {
    Map<String, String> inputs = Map.ofEntries(
        Map.entry("iface", "en4"),
        Map.entry("protocol", "TN3270"),
        Map.entry("protocolDefaultFilter.TN3270", "tcp port 3023"));

    CaptureConfig cfg = CaptureConfig.fromMap(inputs);

    assertEquals("tcp port 3023", cfg.filter());
    assertEquals(CaptureProtocol.TN3270, cfg.protocol());
    assertFalse(cfg.customBpfEnabled());
  }

  @Test
  void tn3270SampleRateOutsideBoundsFails() {
    Map<String, String> inputs = Map.of(
        "iface", "en0",
        "tn3270.screenRenderSampleRate", "1.5");
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> CaptureConfig.fromMap(inputs));
    assertTrue(ex.getMessage().contains("tn3270.screenRenderSampleRate"));
  }

  @Test
  void customBpfRequiresEnableFlag() {
    Map<String, String> inputs = Map.of("iface", "en0", "bpf", "tcp port 80");
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> CaptureConfig.fromMap(inputs));
    assertTrue(ex.getMessage().contains("--enable-bpf"));
  }

  @Test
  void kafkaModeValidatesBootstrapAndTopic() {
    Map<String, String> noBootstrap = Map.of("iface", "en0", "out", "kafka:radar.capture");
    IllegalArgumentException missingBootstrap =
        assertThrows(IllegalArgumentException.class, () -> CaptureConfig.fromMap(noBootstrap));
    assertTrue(missingBootstrap.getMessage().contains("kafkaBootstrap"));

    Map<String, String> invalidBootstrap = Map.ofEntries(
        Map.entry("iface", "en0"),
        Map.entry("out", "kafka:radar.capture"),
        Map.entry("kafkaBootstrap", "localhost"));
    IllegalArgumentException malformed =
        assertThrows(IllegalArgumentException.class, () -> CaptureConfig.fromMap(invalidBootstrap));
    assertTrue(malformed.getMessage().contains("host:port"));
  }

  @Test
  void persistenceQueueCapacityHonoursBounds() {
    Map<String, String> inputs = Map.ofEntries(
        Map.entry("iface", "en0"),
        Map.entry("persistWorkers", "4"),
        Map.entry("persistQueueCapacity", "2"));
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> CaptureConfig.fromMap(inputs));
    assertTrue(ex.getMessage().contains("persistQueueCapacity"));
  }
}

