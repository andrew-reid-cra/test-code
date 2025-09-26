package ca.gc.cra.radar.validation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * <strong>What:</strong> Filesystem validation utilities for RADAR CLI and configuration flows.
 * <p><strong>Why:</strong> Ensures capture, assemble, and sink components write only to sanctioned,
 * writable directories and avoids accidental data loss.
 * <p><strong>Role:</strong> Domain support utilities executed before adapters open PCAP files or
 * persistence sinks.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Normalize user-provided paths to real, canonical directories.</li>
 *   <li>Enforce base-directory sandboxes to protect privileged capture outputs.</li>
 *   <li>Guard against reuse of populated directories unless explicitly approved.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Stateless methods; concurrency limited by underlying filesystem semantics.</p>
 * <p><strong>Performance:</strong> Bounded filesystem metadata checks; no long-lived resources beyond directory handles.</p>
 * <p><strong>Observability:</strong> Emits no metrics or logs; callers surface validation exceptions.</p>
 *
 * @implNote All filesystem checks use {@link LinkOption#NOFOLLOW_LINKS} to avoid accidental symlink traversal when
 * validating capture output targets.
 * @since 0.1.0
 * @see Strings
 * @see Numbers
 */
public final class Paths {
  private Paths() {
    // Utility
  }

  /**
   * Validates a writable directory without enforcing a base path constraint.
   *
   * @param path candidate directory for capture or sink output; must not be {@code null}
   * @return canonical directory path when available, otherwise the absolute normalized path
   * @throws IllegalArgumentException if the directory is missing, not writable, or contains entries
   *
   * <p><strong>Concurrency:</strong> Thread-safe; relies on atomic filesystem metadata lookups.</p>
   * <p><strong>Performance:</strong> Executes a handful of {@link Files} checks; no iteration unless verifying emptiness.</p>
   * <p><strong>Observability:</strong> No logging; callers should translate exceptions into CLI guidance.</p>
   */
  public static Path validateWritableDir(Path path) {
    return validateWritableDir(path, null, false, false);
  }

  /**
   * Validates a writable directory, optionally enforcing a base sandbox and creating the directory if missing.
   *
   * @param path candidate directory for capture artifacts; must not be {@code null}
   * @param allowedBase optional base directory; when non-null, {@code path} must reside within it
   * @param createIfMissing whether to create the directory (and parents) when absent
   * @param allowReuse when {@code false}, existing non-empty directories are rejected to avoid overwriting
   * @return canonical directory path when available, otherwise the absolute normalized path
   * @throws IllegalArgumentException if the path escapes {@code allowedBase}, is non-writable, or creation fails
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent invocations; filesystem state may change between checks.</p>
   * <p><strong>Performance:</strong> Performs {@code O(depth)} canonicalization and bounded directory scans.</p>
   * <p><strong>Observability:</strong> Emits no metrics; exception messages include offending paths.</p>
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
