package ca.gc.cra.radar.infrastructure.poster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Comparator;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Test utilities shared by poster pipeline tests. */
final class PosterFixtures {
  private PosterFixtures() {}

  static Path tempDir(String prefix) throws IOException {
    return Files.createTempDirectory(prefix, new FileAttribute<?>[0]);
  }

  static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var stream = Files.walk(root)) {
      stream.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
      });
    }
  }

  static void write(Path path, byte[] data) throws IOException {
    Files.createDirectories(path.getParent());
    Files.write(path, data);
  }

  static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
      gzip.write(data);
    }
    return buffer.toByteArray();
  }

  static byte[] deflate(byte[] data) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater = new DeflaterOutputStream(buffer)) {
      deflater.write(data);
    }
    return buffer.toByteArray();
  }

  static String httpIndexEntry(
      String id,
      String kind,
      long tsFirst,
      long tsLast,
      String src,
      String dst,
      String firstLine,
      String headersBlob,
      long headersLen,
      String bodyBlob,
      long bodyLen) {
    return "{" +
        quote("id") + ":" + quote(id) + ',' +
        quote("kind") + ":" + quote(kind) + ',' +
        quote("ts_first") + ':' + tsFirst + ',' +
        quote("ts_last") + ':' + tsLast + ',' +
        quote("src") + ":" + quote(src) + ',' +
        quote("dst") + ":" + quote(dst) + ',' +
        quote("first_line") + ":" + (firstLine != null ? quote(firstLine) : "null") + ',' +
        quote("headers_blob") + ":" + (headersBlob != null ? quote(headersBlob) : "null") + ',' +
        quote("headers_off") + ":0," +
        quote("headers_len") + ':' + headersLen + ',' +
        quote("body_blob") + ":" + (bodyBlob != null ? quote(bodyBlob) : "null") + ',' +
        quote("body_off") + ":0," +
        quote("body_len") + ':' + bodyLen +
        "}";
  }

  static String tnIndexEntry(
      String id,
      String kind,
      long tsFirst,
      long tsLast,
      String src,
      String dst,
      String bodyBlob,
      long bodyLen) {
    return "{" +
        quote("id") + ":" + quote(id) + ',' +
        quote("kind") + ":" + quote(kind) + ',' +
        quote("ts_first") + ':' + tsFirst + ',' +
        quote("ts_last") + ':' + tsLast + ',' +
        quote("src") + ":" + quote(src) + ',' +
        quote("dst") + ":" + quote(dst) + ',' +
        quote("first_line") + ":null," +
        quote("headers_blob") + ":null," +
        quote("headers_off") + ":0," +
        quote("headers_len") + ":0," +
        quote("body_blob") + ":" + (bodyBlob != null ? quote(bodyBlob) : "null") + ',' +
        quote("body_off") + ":0," +
        quote("body_len") + ':' + bodyLen +
        "}";
  }

  private static String quote(String value) {
    return '"' + value + '"';
  }
}
