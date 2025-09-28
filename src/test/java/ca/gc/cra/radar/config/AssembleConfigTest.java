package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssembleConfigTest {
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
  void defaultsUseSandboxUnderUserHome() {
    AssembleConfig config = AssembleConfig.fromMap(Map.of());
    AssembleConfig defaults = AssembleConfig.defaults();

    assertEquals(IoMode.FILE, config.ioMode());
    assertEquals(defaults.inputDirectory(), config.inputDirectory());
    assertEquals(defaults.outputDirectory(), config.outputDirectory());
    assertEquals("radar.segments", config.kafkaSegmentsTopic());
    assertTrue(config.effectiveHttpOut().endsWith(Path.of("assemble", "http")));
    assertTrue(config.effectiveTnOut().endsWith(Path.of("assemble", "tn3270")));
    assertFalse(config.tn3270EmitScreenRenders());
    assertEquals(0d, config.tn3270ScreenRenderSampleRate());
    assertEquals("", config.tn3270RedactionPolicy());
  }

  @Test
  void infersKafkaModeFromKafkaInput() {
    AssembleConfig config = AssembleConfig.fromMap(Map.of(
        "in", "kafka:radar.capture",
        "kafkaBootstrap", "localhost:9092"));

    assertEquals(IoMode.KAFKA, config.ioMode());
    assertEquals("localhost:9092", config.kafkaBootstrap().orElseThrow());
    assertEquals("radar.capture", config.kafkaSegmentsTopic());
  }

  @Test
  void rejectsKafkaModeWithoutBootstrap() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        AssembleConfig.fromMap(Map.of("in", "kafka:radar.capture")));
    assertTrue(ex.getMessage().contains("kafkaBootstrap"));
  }

  @Test
  void effectiveOutputPathsFallbackToDerivedSubdirectories() {
    Path out = tempDir.resolve("pairs-out");
    AssembleConfig config = AssembleConfig.fromMap(Map.of(
        "in", tempDir.resolve("segments").toString(),
        "out", out.toString()));

    assertTrue(config.effectiveHttpOut().endsWith(Path.of("pairs-out", "http")));
    assertTrue(config.effectiveTnOut().endsWith(Path.of("pairs-out", "tn3270")));
  }

  @Test
  void tn3270SampleRateOutsideBoundsFails() {
    Map<String, String> inputs = Map.of(
        "tn3270.emitScreenRenders", "true",
        "tn3270.screenRenderSampleRate", "-0.1");
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> AssembleConfig.fromMap(inputs));
    assertTrue(ex.getMessage().contains("tn3270.screenRenderSampleRate"));
  }

  @Test
  void atLeastOneProtocolMustBeEnabled() {
    assertThrows(IllegalArgumentException.class, () ->
        AssembleConfig.fromMap(Map.of(
            "httpEnabled", "false",
            "tnEnabled", "false")));
  }
}
