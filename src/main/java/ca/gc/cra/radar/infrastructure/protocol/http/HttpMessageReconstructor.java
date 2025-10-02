package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.infrastructure.buffer.GrowableBuffer;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.msg.TransactionId;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight HTTP/1.x reconstructor that buffers per-direction payloads and emits complete
 * header+body messages when Content-Length allows. Chunked bodies are emitted best-effort as a
 * single payload containing the buffered bytes at the time of detection.
 */
public final class HttpMessageReconstructor implements MessageReconstructor {
  private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CONTENT_LENGTH = "content-length".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] TRANSFER_ENCODING = "transfer-encoding".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CHUNKED = "chunked".getBytes(StandardCharsets.US_ASCII);

  private final ClockPort clock;
  private final HttpMetrics metrics;

  private DirectionState client;
  private DirectionState server;
  private final Deque<String> pendingTransactionIds = new ArrayDeque<>();
  private static final Map<String, String> NO_ATTRIBUTES = Map.of();

  /**
   * Creates a reconstructor bound to the supplied clock and metrics sink.
   *
   * @param clock clock used for timestamping generated events
   * @param metrics metrics sink for HTTP-specific counters
   * @throws NullPointerException if {@code clock} or {@code metrics} is {@code null}
   * @since RADAR 0.1-doc
   */
  public HttpMessageReconstructor(ClockPort clock, MetricsPort metrics) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.metrics = new HttpMetrics(Objects.requireNonNull(metrics, "metrics"));
  }

  /**
   * Resets parser state at the beginning of a session.
   *
   * @implNote Clears buffers for both directions and pending transaction ids.
   * @since RADAR 0.1-doc
   */
  @Override
  public void onStart() {
    if (client == null) {
      client = new DirectionState(true);
    } else {
      client.reset();
    }
    if (server == null) {
      server = new DirectionState(false);
    } else {
      server.reset();
    }
    pendingTransactionIds.clear();
  }

  /**
   * Parses HTTP bytes and emits zero or more message events.
   *
   * @param slice contiguous byte stream slice with flow metadata; must not be {@code null}
   * @return list of message events extracted from the slice; may be empty
   * @throws NullPointerException if {@code slice} is {@code null}
   * @implNote Associates responses with the oldest unmatched request id; chunked bodies emit buffered bytes once detected.
   * @since RADAR 0.1-doc
   */
  @Override
  public List<MessageEvent> onBytes(ByteStream slice) {
    if (client == null || server == null) {
      onStart();
    }
    metrics.onBytes(slice.data().length);
    DirectionState dir = slice.fromClient() ? client : server;
    dir.append(slice);

    List<ByteStream> messages = dir.drain(slice.flow());
    List<MessageEvent> events = new ArrayList<>(messages.size());
    for (ByteStream message : messages) {
      boolean fromClient = message.fromClient();
      MessageType type = fromClient ? MessageType.REQUEST : MessageType.RESPONSE;
      String transactionId;
      if (fromClient) {
        transactionId = TransactionId.newId(clock.nowMillis());
        pendingTransactionIds.addLast(transactionId);
      } else {
        transactionId = pendingTransactionIds.isEmpty()
            ? TransactionId.newId(clock.nowMillis())
            : pendingTransactionIds.removeFirst();
      }
      MessageMetadata metadata = new MessageMetadata(transactionId, NO_ATTRIBUTES);
      events.add(new MessageEvent(ProtocolId.HTTP, type, message, metadata));
    }
    return events;
  }

  /**
   * Clears buffers at the end of the session.
   *
   * @implNote Pending transaction ids are dropped to avoid leaking across sessions.
   * @since RADAR 0.1-doc
   */
  @Override
  public void onClose() {
    pendingTransactionIds.clear();
    if (client != null) {
      client.reset();
    }
    if (server != null) {
      server.reset();
    }
  }

  private static final class DirectionState {
    private final boolean fromClient;
    private final GrowableBuffer buffer = new GrowableBuffer();
    private long lastTimestamp;

    DirectionState(boolean fromClient) {
      this.fromClient = fromClient;
    }

    void append(ByteStream slice) {
      buffer.write(slice.data());
      lastTimestamp = slice.timestampMicros();
    }

    List<ByteStream> drain(FiveTuple flow) {
      List<ByteStream> messages = new ArrayList<>();
      while (true) {
        int headerEnd = buffer.indexOf(CRLFCRLF);
        if (headerEnd < 0) {
          break;
        }
        int headersLen = headerEnd + CRLFCRLF.length;
        if (buffer.readableBytes() < headersLen) {
          break;
        }
        int bodyLen = bodyLength(buffer, headersLen, fromClient);
        if (bodyLen < 0) {
          int readable = buffer.readableBytes();
          byte[] payload = buffer.copy(readable);
          messages.add(new ByteStream(flow, fromClient, payload, lastTimestamp));
          break;
        }
        int required = headersLen + bodyLen;
        if (buffer.readableBytes() < required) {
          buffer.ensureCapacity(required);
          break;
        }
        byte[] payload = buffer.copy(required);
        messages.add(new ByteStream(flow, fromClient, payload, lastTimestamp));
      }
      return messages;
    }

    void reset() {
      buffer.clear();
      lastTimestamp = 0L;
    }
  }

  private static int bodyLength(GrowableBuffer buffer, int headersLen, boolean request) {
    int start = buffer.readerIndex();
    int limit = start + headersLen;
    int lineStart = start;
    int firstLineEnd = -1;
    int contentLength = -1;
    boolean chunked = false;

    while (lineStart < limit) {
      int newline = lineStart;
      while (newline < limit && buffer.byteAt(newline) != '\n') {
        newline++;
      }
      if (newline >= limit) {
        break;
      }
      int lineEnd = newline;
      if (lineEnd > lineStart && buffer.byteAt(lineEnd - 1) == '\r') {
        lineEnd--;
      }
      if (firstLineEnd < 0) {
        firstLineEnd = lineEnd;
      }
      int colon = -1;
      for (int i = lineStart; i < lineEnd; i++) {
        if (buffer.byteAt(i) == ':') {
          colon = i;
          break;
        }
      }
      if (colon > lineStart) {
        int nameStart = lineStart;
        while (nameStart < colon && isLinearWhitespace(buffer.byteAt(nameStart))) {
          nameStart++;
        }
        int nameEnd = colon;
        while (nameEnd > nameStart && isLinearWhitespace(buffer.byteAt(nameEnd - 1))) {
          nameEnd--;
        }
        int valueStart = colon + 1;
        while (valueStart < lineEnd && isLinearWhitespace(buffer.byteAt(valueStart))) {
          valueStart++;
        }
        int valueEnd = lineEnd;
        while (valueEnd > valueStart && isLinearWhitespace(buffer.byteAt(valueEnd - 1))) {
          valueEnd--;
        }
        if (equalsIgnoreCase(buffer, nameStart, nameEnd, CONTENT_LENGTH)) {
          int parsed = parseContentLength(buffer, valueStart, valueEnd);
          contentLength = parsed >= 0 ? parsed : 0;
        } else if (equalsIgnoreCase(buffer, nameStart, nameEnd, TRANSFER_ENCODING)) {
          if (containsTokenIgnoreCase(buffer, valueStart, valueEnd, CHUNKED)) {
            chunked = true;
          }
        }
      }
      lineStart = newline + 1;
    }

    if (chunked) {
      return -1;
    }
    if (contentLength >= 0) {
      return contentLength;
    }
    if (!request && firstLineEnd > start) {
      int status = parseStatusCode(buffer, start, firstLineEnd);
      if ((status >= 100 && status < 200) || status == 204 || status == 304) {
        return 0;
      }
    }
    return request ? 0 : -1;
  }

  private static boolean equalsIgnoreCase(GrowableBuffer buffer, int start, int end, byte[] token) {
    int length = end - start;
    if (length != token.length) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (toLowerAscii(buffer.byteAt(start + i)) != token[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsTokenIgnoreCase(GrowableBuffer buffer, int start, int end, byte[] token) {
    int needed = token.length;
    if (needed == 0) {
      return false;
    }
    for (int i = start; i <= end - needed; i++) {
      if (equalsIgnoreCase(buffer, i, i + needed, token)) {
        boolean leftDelim = i == start || isTokenSeparator(buffer.byteAt(i - 1));
        int rightIndex = i + needed;
        boolean rightDelim = rightIndex >= end || isTokenSeparator(buffer.byteAt(rightIndex));
        if (leftDelim && rightDelim) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isTokenSeparator(byte b) {
    return b == ',' || isLinearWhitespace(b);
  }

  private static int parseContentLength(GrowableBuffer buffer, int start, int end) {
    long value = 0L;
    boolean digits = false;
    for (int i = start; i < end; i++) {
      byte b = buffer.byteAt(i);
      if (b >= '0' && b <= '9') {
        digits = true;
        value = value * 10 + (b - '0');
        if (value > Integer.MAX_VALUE) {
          return Integer.MAX_VALUE;
        }
      } else if (isLinearWhitespace(b)) {
        continue;
      } else {
        return -1;
      }
    }
    return digits ? (int) value : -1;
  }

  private static int parseStatusCode(GrowableBuffer buffer, int start, int end) {
    int idx = start;
    while (idx < end && buffer.byteAt(idx) != ' ') {
      idx++;
    }
    while (idx < end && buffer.byteAt(idx) == ' ') {
      idx++;
    }
    int digits = 0;
    int value = 0;
    while (idx < end && digits < 3) {
      byte b = buffer.byteAt(idx);
      if (b < '0' || b > '9') {
        break;
      }
      value = (value * 10) + (b - '0');
      idx++;
      digits++;
    }
    return digits == 3 ? value : -1;
  }

  private static boolean isLinearWhitespace(byte b) {
    return b == ' ' || b == '\t';
  }

  private static byte toLowerAscii(byte b) {
    return (byte) (b >= 'A' && b <= 'Z' ? b + 32 : b);
  }

}

