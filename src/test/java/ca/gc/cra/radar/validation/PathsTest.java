package ca.gc.cra.radar.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathsTest {

  @TempDir Path tempDir;

  @Test
  void validateWritableDirReturnsCanonicalPathWhenDirectoryExists() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("existing"));
    Path validated = Paths.validateWritableDir(dir, null, false, true);
    assertEquals(dir.toRealPath(), validated);
  }

  @Test
  void validateWritableDirRejectsNonEmptyDirectoryWithoutAllowOverwrite() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("nonEmpty"));
    Files.createFile(dir.resolve("file.txt"));
    assertThrows(IllegalArgumentException.class, () ->
        Paths.validateWritableDir(dir, null, false, false));
  }

  @Test
  void validateWritableDirAllowsCreationWhenRequested() throws IOException {
    Path dir = tempDir.resolve("missing");
    Path validated = Paths.validateWritableDir(dir, null, true, false);
    assertTrue(Files.exists(validated));
  }

  @Test
  void validateWritableDirAllowsFutureCreationDuringDryRun() {
    Path dir = tempDir.resolve("future/child");
    Path validated = Paths.validateWritableDir(dir, null, false, false);
    assertTrue(validated.toString().endsWith("future\\child") || validated.toString().endsWith("future/child"));
  }
}

