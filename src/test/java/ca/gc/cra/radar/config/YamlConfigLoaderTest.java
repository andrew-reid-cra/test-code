package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigLoaderTest {

  @TempDir Path tempDir;

  @Test
  void loadMergesCommonAndModeSections() throws IOException {
    Path yaml = tempDir.resolve("radar.yaml");
    Files.writeString(yaml, """
        common:
          metricsExporter: none
        capture:
          iface: en1
          bufmb: 512
        """);

    Optional<Map<String, String>> result = YamlConfigLoader.load(yaml, "capture");

    assertTrue(result.isPresent());
    Map<String, String> map = result.orElseThrow();
    assertEquals("none", map.get("metricsExporter"));
    assertEquals("en1", map.get("iface"));
    assertEquals("512", map.get("bufmb"));
  }

  @Test
  void loadFlattensNestedMaps() throws IOException {
    Path yaml = tempDir.resolve("nested.yaml");
    Files.writeString(yaml, """
        common:
          tn3270:
            emitScreenRenders: true
        poster:
          tn3270:
            output:
              path: /tmp/tn
        """);

    Map<String, String> map = YamlConfigLoader.load(yaml, "poster").orElseThrow();
    assertEquals("true", map.get("tn3270.emitScreenRenders"));
    assertEquals("/tmp/tn", map.get("tn3270.output.path"));
  }

  @Test
  void missingFileReturnsEmptyOptional() throws IOException {
    Optional<Map<String, String>> result =
        YamlConfigLoader.load(tempDir.resolve("missing.yaml"), "capture");

    assertFalse(result.isPresent());
  }

  @Test
  void invalidRootStructureThrows() throws IOException {
    Path yaml = tempDir.resolve("invalid.yaml");
    Files.writeString(yaml, """
        - capture:
            iface: en0
        """);

    assertThrows(IllegalArgumentException.class, () -> YamlConfigLoader.load(yaml, "capture"));
  }
}
