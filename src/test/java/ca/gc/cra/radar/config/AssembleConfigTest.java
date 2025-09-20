package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssembleConfigTest {

  @Test
  void defaultsToFileMode() {
    AssembleConfig config = AssembleConfig.fromMap(Map.of());
    assertEquals(IoMode.FILE, config.ioMode());
    assertEquals("radar.segments", config.kafkaSegmentsTopic());
    assertEquals(Path.of("./cap-out"), config.inputDirectory());
  }

  @Test
  void infersKafkaModeFromInputPrefix() {
    AssembleConfig config = AssembleConfig.fromMap(Map.of(
        "in", "kafka:radar.capture",
        "kafkaBootstrap", "localhost:9092"));
    assertEquals(IoMode.KAFKA, config.ioMode());
    assertEquals("radar.capture", config.kafkaSegmentsTopic());
    assertEquals("localhost:9092", config.kafkaBootstrap().orElse(null));
  }

  @Test
  void rejectsKafkaModeWithoutBootstrap() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        AssembleConfig.fromMap(Map.of(
            "in", "kafka:radar.capture")));
    assertNotNull(ex.getMessage());
  }

  @Test
  void effectiveOutputPathsFallbackToPerProtocolFolders() {
    AssembleConfig config = AssembleConfig.fromMap(Map.of(
        "in", "./segments",
        "out", "./outdir"));
    assertEquals(Path.of("./outdir/http"), config.effectiveHttpOut());
    assertEquals(Path.of("./outdir/tn3270"), config.effectiveTnOut());
  }

  @Test
  void atLeastOneProtocolMustBeEnabled() {
    assertThrows(IllegalArgumentException.class, () ->
        AssembleConfig.fromMap(Map.of(
            "httpEnabled", "false",
            "tnEnabled", "false")));
  }
}
