package ca.gc.cra.radar.infrastructure.poster;

import ca.gc.cra.radar.config.PosterConfig.DecodeMode;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Poster pipeline that reads NDJSON index/blob files and emits human-readable transaction dumps.
 */
final class SegmentPosterProcessor {

  private static final Logger log = LoggerFactory.getLogger(SegmentPosterProcessor.class);

  void process(Path inputDirectory, Path outputDirectory, ProtocolId protocol, DecodeMode decodeMode)
      throws Exception {
    Objects.requireNonNull(inputDirectory, "inputDirectory");
    Objects.requireNonNull(outputDirectory, "outputDirectory");
    Objects.requireNonNull(protocol, "protocol");
    Objects.requireNonNull(decodeMode, "decodeMode");
    Files.createDirectories(outputDirectory);

    if (!Files.exists(inputDirectory) || !Files.isDirectory(inputDirectory)) {
      throw new IllegalArgumentException("input directory does not exist: " + inputDirectory);
    }

    List<Path> indexFiles = collectIndexFiles(inputDirectory);
    Map<String, PairAccumulator> pending = new LinkedHashMap<>();
    for (Path indexFile : indexFiles) {
      processIndexFile(indexFile, pending, outputDirectory, protocol, decodeMode);
    }

    for (PairAccumulator acc : pending.values()) {
      if (acc.hasAny()) {
        writePair(acc, outputDirectory, protocol, decodeMode);
      }
    }
  }


  private boolean matchesProtocol(IndexEntry entry, ProtocolId protocol) {
    if (protocol == ProtocolId.HTTP) {
      return entry.headersLength() > 0;
    }
    if (protocol == ProtocolId.TN3270) {
      return entry.headersLength() == 0;
    }
    return false;
  }

  private List<Path> collectIndexFiles(Path directory) throws IOException {
    try (Stream<Path> stream = Files.list(directory)) {
      return stream
          .filter(p -> Files.isRegularFile(p))
          .filter(p -> {
            String name = p.getFileName().toString();
            return name.startsWith("index-") && name.endsWith(".ndjson");
          })
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private void processIndexFile(Path indexFile, Map<String, PairAccumulator> pending, Path outputDirectory, ProtocolId protocol, DecodeMode decodeMode)
      throws Exception {
    try (BufferedReader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        IndexEntry entry = IndexEntry.parse(line, indexFile.getParent());
        if (entry == null) {
          continue;
        }
        if (!matchesProtocol(entry, protocol)) {
          continue;
        }
        PairAccumulator acc = pending.computeIfAbsent(entry.id(), PairAccumulator::new);
        acc.add(entry);
        if (acc.isComplete()) {
          writePair(acc, outputDirectory, protocol, decodeMode);
          pending.remove(entry.id());
        }
      }
    }
  }

  private void writePair(
      PairAccumulator acc,
      Path outputDirectory,
      ProtocolId protocol,
      DecodeMode decodeMode) throws Exception {
    if (protocol == ProtocolId.HTTP) {
      writeHttpPair(acc, outputDirectory, decodeMode);
    } else if (protocol == ProtocolId.TN3270) {
      writeTnPair(acc, outputDirectory);
    }
  }

  private void writeHttpPair(PairAccumulator acc, Path outputDirectory, DecodeMode decodeMode) throws Exception {
    HttpPart request = acc.request() != null ? loadHttpPart(acc.request()) : null;
    HttpPart response = acc.response() != null ? loadHttpPart(acc.response()) : null;

    if (request != null) {
      decodeHttpPart(request, decodeMode);
    }
    if (response != null) {
      decodeHttpPart(response, decodeMode);
    }

    long timestamp = earliestTimestamp(request, response);
    String safeId = sanitize(acc.id());
    Path outFile = outputDirectory.resolve(timestamp + "_" + safeId + ".http");

    try (BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
      writer.write("=== HTTP REQUEST ===");
      writer.newLine();
      if (request != null) {
        writeHttpSection(writer, acc.id(), request);
      } else {
        writer.write("(no request captured)");
        writer.newLine();
      }
      writer.newLine();
      writer.write("=== HTTP RESPONSE ===");
      writer.newLine();
      if (response != null) {
        writeHttpSection(writer, acc.id(), response);
      } else {
        writer.write("(no response captured)");
        writer.newLine();
      }
    }
  }

  private void writeTnPair(PairAccumulator acc, Path outputDirectory) throws Exception {
    BinaryPart request = acc.request() != null ? loadBinaryPart(acc.request()) : null;
    BinaryPart response = acc.response() != null ? loadBinaryPart(acc.response()) : null;

    long timestamp = earliestTimestamp(request, response);
    String safeId = sanitize(acc.id());
    Path outFile = outputDirectory.resolve(timestamp + "_" + safeId + ".tn3270.txt");

    try (BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
      writer.write("=== TN3270 REQUEST ===");
      writer.newLine();
      if (request != null) {
        writeBinarySection(writer, request);
      } else {
        writer.write("(no request captured)");
        writer.newLine();
      }
      writer.newLine();
      writer.write("=== TN3270 RESPONSE ===");
      writer.newLine();
      if (response != null) {
        writeBinarySection(writer, response);
      } else {
        writer.write("(no response captured)");
        writer.newLine();
      }
    }
  }

  private HttpPart loadHttpPart(IndexEntry entry) throws IOException {
    byte[] headerBytes = readRange(entry.directory(), entry.headersBlob(), entry.headersOffset(), entry.headersLength());
    ParsedHeaders parsed = parseHeaders(headerBytes, entry.firstLine());
    byte[] body = readRange(entry.directory(), entry.bodyBlob(), entry.bodyOffset(), entry.bodyLength());
    return new HttpPart(entry, parsed.startLine(), new ArrayList<>(parsed.headers()), body);
  }

  private BinaryPart loadBinaryPart(IndexEntry entry) throws IOException {
    byte[] payload = readRange(entry.directory(), entry.bodyBlob(), entry.bodyOffset(), entry.bodyLength());
    return new BinaryPart(entry, payload);
  }

  private static String safePartId(HttpPart part) {
    IndexEntry entry = part.entry();
    String id = entry != null ? entry.id() : null;
    return (id == null || id.isBlank()) ? "unknown" : id;
  }

  private void decodeHttpPart(HttpPart part, DecodeMode mode) {
    if (mode.decodeTransferEncoding()) {
      Header transfer = part.findHeader("Transfer-Encoding");
      if (transfer != null && transfer.value().toLowerCase(Locale.ROOT).contains("chunked")) {
        try {
          byte[] decoded = decodeChunked(part.body());
          part.setBody(decoded);
          part.removeHeader("Transfer-Encoding");
          part.removeHeader("Content-Length");
          part.addHeader("Content-Length", Integer.toString(decoded.length));
        } catch (IOException decodeError) {
          log.warn("Failed to decode chunked encoding for HTTP part {}", safePartId(part), decodeError);
        }
      }
    }
    if (mode.decodeContentEncoding()) {
      Header encoding = part.findHeader("Content-Encoding");
      if (encoding != null) {
        String value = encoding.value().toLowerCase(Locale.ROOT);
        try {
          byte[] decoded;
          if (value.contains("gzip")) {
            decoded = decodeGzip(part.body());
          } else if (value.contains("deflate")) {
            decoded = decodeDeflate(part.body());
          } else {
            decoded = null;
          }
          if (decoded != null) {
            part.setBody(decoded);
            part.removeHeader("Content-Encoding");
            part.removeHeader("Content-Length");
            part.addHeader("Content-Length", Integer.toString(decoded.length));
          }
        } catch (IOException decodeError) {
          log.warn("Failed to decode content encoding '{}' for HTTP part {}", value, safePartId(part), decodeError);
        }
      }
    }
  }

  private void writeHttpSection(BufferedWriter writer, String id, HttpPart part) throws IOException {
    writer.write("# id: ");
    writer.write(id);
    writer.newLine();
    writer.write("# timestamp_us: ");
    writer.write(Long.toString(part.entry().tsFirst()));
    writer.newLine();
    writer.write("# src: ");
    writer.write(part.entry().src());
    writer.newLine();
    writer.write("# dst: ");
    writer.write(part.entry().dst());
    writer.newLine();
    writer.newLine();
    writer.write(part.startLine());
    writer.newLine();
    for (Header header : part.headers()) {
      writer.write(header.name());
      writer.write(": ");
      writer.write(header.value());
      writer.newLine();
    }
    writer.newLine();
    writeBody(writer, part.body());
  }

  private void writeBinarySection(BufferedWriter writer, BinaryPart part) throws IOException {
    writer.write("# timestamp_us: ");
    writer.write(Long.toString(part.entry().tsFirst()));
    writer.newLine();
    writer.write("# src: ");
    writer.write(part.entry().src());
    writer.newLine();
    writer.write("# dst: ");
    writer.write(part.entry().dst());
    writer.newLine();
    writer.newLine();
    if (part.payload().length == 0) {
      writer.write("(empty payload)");
      writer.newLine();
    } else {
      writer.write(hexDump(part.payload()));
    }
  }

  private void writeBody(BufferedWriter writer, byte[] body) throws IOException {
    if (body.length == 0) {
      writer.write("(empty body)");
      writer.newLine();
      return;
    }
    if (isLikelyText(body)) {
      String text = new String(body, StandardCharsets.ISO_8859_1);
      writer.write(text);
      if (!endsWithNewline(text)) {
        writer.newLine();
      }
    } else {
      writer.write("[binary payload " + body.length + " bytes]");
      writer.newLine();
      writer.write(hexDump(body));
    }
  }

  private static boolean endsWithNewline(String text) {
    return text.endsWith("\n") || text.endsWith("\r");
  }

  private static byte[] readRange(Path directory, String fileName, long offset, long length) throws IOException {
    if (fileName == null || fileName.isBlank() || length <= 0) {
      return new byte[0];
    }
    if (length > Integer.MAX_VALUE) {
      throw new IOException("segment too large: " + length);
    }
    Path path = directory.resolve(fileName);
    if (!Files.exists(path)) {
      return new byte[0];
    }
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.allocate((int) length);
      channel.read(buffer, offset);
      return buffer.array();
    }
  }

  private static ParsedHeaders parseHeaders(byte[] data, String fallbackFirstLine) throws IOException {
    if (data.length == 0) {
      return new ParsedHeaders(fallbackFirstLine != null ? fallbackFirstLine : "", List.of());
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.ISO_8859_1))) {
      String startLine = reader.readLine();
      if (startLine == null) {
        startLine = fallbackFirstLine != null ? fallbackFirstLine : "";
      }
      List<Header> headers = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          break;
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
          headers.add(new Header(line, ""));
        } else {
          String name = line.substring(0, colon).trim();
          String value = line.substring(colon + 1).trim();
          headers.add(new Header(name, value));
        }
      }
      return new ParsedHeaders(startLine, headers);
    }
  }

  private static byte[] decodeChunked(byte[] body) throws IOException {
    ByteArrayInputStream in = new ByteArrayInputStream(body);
    ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
    while (true) {
      String sizeLine = readChunkLine(in);
      if (sizeLine == null) {
        throw new IOException("unexpected EOF while reading chunk size");
      }
      int semicolon = sizeLine.indexOf(';');
      if (semicolon >= 0) {
        sizeLine = sizeLine.substring(0, semicolon);
      }
      int size = Integer.parseInt(sizeLine.trim(), 16);
      if (size == 0) {
        // consume trailer lines until blank
        while (true) {
          String trailer = readChunkLine(in);
          if (trailer == null || trailer.isEmpty()) {
            break;
          }
        }
        break;
      }
      byte[] chunk = in.readNBytes(size);
      if (chunk.length != size) {
        throw new IOException("incomplete chunk: expected " + size + " bytes");
      }
      out.write(chunk);
      consumeCrlf(in);
    }
    return out.toByteArray();
  }

  private static byte[] decodeGzip(byte[] body) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body));
         ByteArrayOutputStream out = new ByteArrayOutputStream(body.length)) {
      in.transferTo(out);
      return out.toByteArray();
    }
  }

  private static byte[] decodeDeflate(byte[] body) throws IOException {
    try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(body));
         ByteArrayOutputStream out = new ByteArrayOutputStream(body.length)) {
      in.transferTo(out);
      return out.toByteArray();
    }
  }

  private static String readChunkLine(ByteArrayInputStream in) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int b;
    boolean sawCr = false;
    while ((b = in.read()) != -1) {
      if (sawCr) {
        if (b == '\n') {
          break;
        }
        buffer.write('\r');
        sawCr = false;
      }
      if (b == '\r') {
        sawCr = true;
      } else {
        buffer.write(b);
      }
    }
    if (b == -1 && buffer.size() == 0 && !sawCr) {
      return null;
    }
    return buffer.toString(StandardCharsets.ISO_8859_1);
  }

  private static void consumeCrlf(ByteArrayInputStream in) {
    in.mark(2);
    int first = in.read();
    int second = in.read();
    if (!(first == '\r' && second == '\n')) {
      in.reset();
    }
  }

  private static boolean isLikelyText(byte[] data) {
    int printable = 0;
    for (byte b : data) {
      int ch = b & 0xFF;
      if (ch == 9 || ch == 10 || ch == 13) {
        printable++;
        continue;
      }
      if (ch >= 32 && ch <= 126) {
        printable++;
      }
    }
    return printable >= data.length * 0.7;
  }

  private static String hexDump(byte[] data) {
    StringBuilder sb = new StringBuilder((data.length / 16 + 1) * 72);
    for (int i = 0; i < data.length; i += 16) {
      int end = Math.min(data.length, i + 16);
      sb.append(String.format("%04x: ", i));
      for (int j = i; j < end; j++) {
        sb.append(String.format("%02x ", data[j] & 0xFF));
      }
      for (int j = end; j < i + 16; j++) {
        sb.append("   ");
      }
      sb.append(" |");
      for (int j = i; j < end; j++) {
        int ch = data[j] & 0xFF;
        sb.append(ch >= 32 && ch <= 126 ? (char) ch : '.');
      }
      sb.append('|');
      sb.append(System.lineSeparator());
    }
    return sb.toString();
  }

  private static long earliestTimestamp(TemporalPart a, TemporalPart b) {
    if (a == null && b == null) {
      return 0L;
    }
    if (a == null) return b.timestamp();
    if (b == null) return a.timestamp();
    return Math.min(a.timestamp(), b.timestamp());
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private interface TemporalPart {
    /**
     * Timestamp associated with this part, expressed in capture microseconds.
     *
     * @return capture timestamp in microseconds
     * @since RADAR 0.1-doc
     */
    long timestamp();
  }

  private record ParsedHeaders(String startLine, List<Header> headers) {}

  private record Header(String name, String value) {}

  private static final class HttpPart implements TemporalPart {
    private final IndexEntry entry;
    private final String startLine;
    private final List<Header> headers;
    private byte[] body;

    HttpPart(IndexEntry entry, String startLine, List<Header> headers, byte[] body) {
      this.entry = entry;
      this.startLine = startLine != null ? startLine : "";
      this.headers = headers;
      this.body = body != null ? body : new byte[0];
    }

    IndexEntry entry() {
      return entry;
    }

    String startLine() {
      return startLine;
    }

    List<Header> headers() {
      return headers;
    }

    byte[] body() {
      return body;
    }

    void setBody(byte[] body) {
      this.body = body != null ? body : new byte[0];
    }

    Header findHeader(String name) {
      for (Header header : headers) {
        if (header.name().equalsIgnoreCase(name)) {
          return header;
        }
      }
      return null;
    }

    void removeHeader(String name) {
      headers.removeIf(h -> h.name().equalsIgnoreCase(name));
    }

    void addHeader(String name, String value) {
      headers.add(new Header(name, value));
    }

    /**
     * Returns the earliest capture timestamp associated with this HTTP part.
     *
     * @return capture timestamp in microseconds
     * @since RADAR 0.1-doc
     */
    @Override
    public long timestamp() {
      return entry.tsFirst();
    }
  }

  private static final class BinaryPart implements TemporalPart {
    private final IndexEntry entry;
    private final byte[] payload;

    BinaryPart(IndexEntry entry, byte[] payload) {
      this.entry = entry;
      this.payload = payload != null ? payload : new byte[0];
    }

    IndexEntry entry() {
      return entry;
    }

    byte[] payload() {
      return payload;
    }

    /**
     * Returns the earliest capture timestamp for this binary segment.
     *
     * @return capture timestamp in microseconds
     * @since RADAR 0.1-doc
     */
    @Override
    public long timestamp() {
      return entry.tsFirst();
    }
  }

  private static final class PairAccumulator {
    private final String id;
    private IndexEntry request;
    private IndexEntry response;

    PairAccumulator(String id) {
      this.id = id;
    }

    String id() {
      return id;
    }

    IndexEntry request() {
      return request;
    }

    IndexEntry response() {
      return response;
    }

    void add(IndexEntry entry) {
      if (entry.isRequest()) {
        request = entry;
      } else if (entry.isResponse()) {
        response = entry;
      }
    }

    boolean isComplete() {
      return request != null && response != null;
    }

    boolean hasAny() {
      return request != null || response != null;
    }

    boolean isHttp() {
      if (request != null && request.headersLength() > 0) {
        return true;
      }
      if (response != null && response.headersLength() > 0) {
        return true;
      }
      return false;
    }
  }

  private enum Kind {
    REQUEST,
    RESPONSE,
    UNKNOWN;

    static Kind fromString(String value) {
      if (value == null) {
        return UNKNOWN;
      }
      return switch (value.toUpperCase(Locale.ROOT)) {
        case "REQ" -> REQUEST;
        case "RSP" -> RESPONSE;
        default -> UNKNOWN;
      };
    }
  }

  private record IndexEntry(
      Path directory,
      String id,
      Kind kind,
      long tsFirst,
      long tsLast,
      String src,
      String dst,
      String firstLine,
      String headersBlob,
      long headersOffset,
      long headersLength,
      String bodyBlob,
      long bodyOffset,
      long bodyLength) {

    boolean isRequest() {
      return kind == Kind.REQUEST;
    }

    boolean isResponse() {
      return kind == Kind.RESPONSE;
    }

    static IndexEntry parse(String json, Path directory) {
      try {
        String id = extractString(json, "id");
        Kind kind = Kind.fromString(extractString(json, "kind"));
        long tsFirst = extractLong(json, "ts_first");
        long tsLast = extractLong(json, "ts_last");
        String src = extractString(json, "src");
        String dst = extractString(json, "dst");
        String firstLine = extractNullableString(json, "first_line");
        String headersBlob = extractNullableString(json, "headers_blob");
        long headersOff = extractLong(json, "headers_off");
        long headersLen = extractLong(json, "headers_len");
        String bodyBlob = extractNullableString(json, "body_blob");
        long bodyOff = extractLong(json, "body_off");
        long bodyLen = extractLong(json, "body_len");
        return new IndexEntry(directory, id, kind, tsFirst, tsLast, src, dst, firstLine,
            headersBlob, headersOff, headersLen, bodyBlob, bodyOff, bodyLen);
      } catch (Exception ex) {
        return null;
      }
    }
  }

  private static String extractNullableString(String json, String key) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      return null;
    }
    int start = idx + token.length();
    if (json.startsWith("null", start)) {
      return null;
    }
    return extractString(json, key);
  }

  private static String extractString(String json, String key) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      return "";
    }
    int start = json.indexOf('"', idx + token.length());
    if (start < 0) {
      return "";
    }
    StringBuilder value = new StringBuilder();
    boolean escaping = false;
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaping) {
        value.append(c);
        escaping = false;
        continue;
      }
      if (c == '\\') {
        escaping = true;
        continue;
      }
      if (c == '\"') {
        break;
      }
      value.append(c);
    }
    return unescape(value.toString());
  }

  private static long extractLong(String json, String key) {
    String token = '"' + key + '"' + ":";
    int idx = json.indexOf(token);
    if (idx < 0) {
      return 0L;
    }
    int start = idx + token.length();
    int end = start;
    while (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) {
      end++;
    }
    if (end == start) {
      return 0L;
    }
    return Long.parseLong(json.substring(start, end));
  }

  private static String unescape(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    boolean escaping = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (escaping) {
        switch (c) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          default -> sb.append(c);
        }
        escaping = false;
      } else if (c == '\\') {
        escaping = true;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}














