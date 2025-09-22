package ca.gc.cra.radar.validation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Path validation helpers to guard CLI/config supplied directories.
 */
public final class Paths {
  private Paths() {
    // Utility
  }

  /**
   * Validates a directory path ensuring it is writable and safe to use.
   *
   * @param path candidate path
   * @return canonical path when available, otherwise absolute normalized path
   */
  public static Path validateWritableDir(Path path) {
    return validateWritableDir(path, null, false, false);
  }

  /**
   * Validates a directory path ensuring it stays within an allowed base directory.
   *
   * @param path candidate path
   * @param allowedBase optional base directory constraint
   * @param createIfMissing whether the directory should be created when missing
   * @param allowReuse when {@code false}, non-empty directories are rejected
   * @return canonical path when available, otherwise absolute normalized path
   */
  public static Path validateWritableDir(Path path, Path allowedBase, boolean createIfMissing, boolean allowReuse) {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
    String raw = path.toString();
    if (raw.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("path must not contain null bytes");
    }
    if (containsControl(raw)) {
      throw new IllegalArgumentException("path must not contain control characters");
    }

    Path normalized = path.toAbsolutePath().normalize();
    Path base = allowedBase == null ? null : allowedBase.toAbsolutePath().normalize();
    ensureWithinBase(normalized, base);

    try {
      if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
        Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        ensureWithinBase(real, base);
        ensureDirectory(real, allowReuse);
        return real;
      }

      Path parent = normalized.getParent();
      if (parent == null) {
        throw new IllegalArgumentException("path has no parent to validate: " + normalized);
      }
      Path parentNormalized = parent.toAbsolutePath().normalize();
      ensureWithinBase(parentNormalized, base);
      Path parentReal;
      if (!Files.exists(parentNormalized, LinkOption.NOFOLLOW_LINKS)) {
        if (createIfMissing) {
          Files.createDirectories(parentNormalized);
          parentReal = parentNormalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } else {
          parentReal = nearestExistingAncestor(parentNormalized);
        }
      } else {
        parentReal = parentNormalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
      }
      ensureWithinBase(parentReal, base);
      if (!Files.isDirectory(parentReal, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("parent is not a directory: " + parentReal);
      }
      if (!Files.isWritable(parentReal)) {
        throw new IllegalArgumentException("parent directory is not writable: " + parentReal);
      }
      if (createIfMissing) {
        Files.createDirectories(normalized);
        Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        ensureWithinBase(real, base);
        ensureDirectory(real, allowReuse);
        return real;
      }
      return normalized;
    } catch (IOException ex) {
      throw new IllegalArgumentException("unable to validate directory " + normalized + ": " + ex.getMessage(), ex);
    }
  }

  private static void ensureDirectory(Path dir, boolean allowReuse) throws IOException {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("path is not a directory: " + dir);
    }
    if (!Files.isWritable(dir)) {
      throw new IllegalArgumentException("directory is not writable: " + dir);
    }
    if (!allowReuse) {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
        if (entries.iterator().hasNext()) {
          throw new IllegalArgumentException(
              "directory " + dir + " is not empty; re-run with --allow-overwrite to reuse");
        }
      }
    }
  }

  private static void ensureWithinBase(Path candidate, Path base) {
    if (base == null) {
      return;
    }
    if (!candidate.startsWith(base)) {
      throw new IllegalArgumentException("path " + candidate + " escapes allowed base " + base);
    }
  }

  private static Path nearestExistingAncestor(Path start) throws IOException {
    Path current = start;
    while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalArgumentException("no existing ancestor for " + start);
    }
    return current.toRealPath(LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean containsControl(CharSequence value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}


