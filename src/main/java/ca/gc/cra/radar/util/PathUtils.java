package ca.gc.cra.radar.util;

import java.nio.file.Path;
import java.util.Optional;

/** Utility helpers for working with {@link Path} instances. */
public final class PathUtils {
  private PathUtils() {}

  /**
   * Returns the file name for the supplied path when available.
   *
   * @param path source path; may be {@code null}
   * @return optional file name string
   */
  public static Optional<String> fileName(Path path) {
    if (path == null) {
      return Optional.empty();
    }
    Path name = path.getFileName();
    return name == null ? Optional.empty() : Optional.of(name.toString());
  }
}
