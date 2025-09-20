package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
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
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight HTTP/1.x reconstructor that buffers per-direction payloads and emits complete
 * header+body messages when Content-Length allows. Chunked bodies are emitted best-effort as a
 * single payload containing the buffered bytes at the time of detection.
 */
public final class HttpMessageReconstructor implements MessageReconstructor {
  private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private final ClockPort clock;
  private final HttpMetrics metrics;

  private DirectionState client;
  private DirectionState server;
  private final Deque<String> pendingTransactionIds = new ArrayDeque<>();

  public HttpMessageReconstructor(ClockPort clock, MetricsPort metrics) {
    this.clock = clock;
    this.metrics = new HttpMetrics(metrics);
  }

  @Override
  public void onStart() {
    client = new DirectionState(true);
    server = new DirectionState(false);
    pendingTransactionIds.clear();
  }

  @Override
  public List<MessageEvent> onBytes(ByteStream slice) {
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
        transactionId = TransactionId.newId();
        pendingTransactionIds.addLast(transactionId);
      } else {
        transactionId = pendingTransactionIds.isEmpty()
            ? TransactionId.newId()
            : pendingTransactionIds.removeFirst();
      }
      MessageMetadata metadata = new MessageMetadata(transactionId, Map.of());
      events.add(new MessageEvent(ProtocolId.HTTP, type, message, metadata));
    }
    return events;
  }

  @Override
  public void onClose() {
    pendingTransactionIds.clear();
    client.reset();
    server.reset();
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
        byte[] headers = buffer.peek(headersLen);
        int bodyLen = bodyLength(headers, fromClient);
        if (bodyLen < 0) {
          // chunked or unsupported framing: emit everything currently buffered
          byte[] payload = buffer.pop(buffer.size());
          messages.add(new ByteStream(flow, fromClient, payload, lastTimestamp));
          break;
        }
        if (buffer.size() < headersLen + bodyLen) {
          break; // wait for more bytes
        }
        byte[] payload = buffer.pop(headersLen + bodyLen);
        messages.add(new ByteStream(flow, fromClient, payload, lastTimestamp));
      }
      return messages;
    }

    void reset() {
      buffer.clear();
      lastTimestamp = 0L;
    }
  }

  private static int bodyLength(byte[] headers, boolean request) {
    String head = new String(headers, StandardCharsets.ISO_8859_1);
    String lower = head.toLowerCase(Locale.ROOT);

    if (lower.contains("transfer-encoding:") && lower.contains("chunked")) {
      return -1;
    }

    int contentLengthIdx = lower.indexOf("content-length:");
    if (contentLengthIdx >= 0) {
      int endLine = lower.indexOf('\n', contentLengthIdx);
      if (endLine < 0) {
        endLine = lower.length();
      }
      String value = lower.substring(contentLengthIdx + "content-length:".length(), endLine).trim();
      try {
        return Math.max(0, Integer.parseInt(value));
      } catch (NumberFormatException ignore) {
        return 0;
      }
    }

    if (!request) {
      String firstLine = firstLine(head);
      if (firstLine.length() >= 12) {
        // HTTP/1.x <status>
        int codeStart = firstLine.indexOf(' ');
        if (codeStart > 0 && codeStart + 3 <= firstLine.length()) {
          try {
            int code = Integer.parseInt(firstLine.substring(codeStart + 1, codeStart + 4));
            if ((code >= 100 && code < 200) || code == 204 || code == 304) {
              return 0;
            }
          } catch (NumberFormatException ignore) {
            return 0;
          }
        }
      }
    }
    return request ? 0 : 0;
  }

  private static String firstLine(String headers) {
    int idx = headers.indexOf('\n');
    if (idx < 0) {
      return headers.trim();
    }
    return headers.substring(0, idx).trim();
  }

  private static final class GrowableBuffer {
    private byte[] data = new byte[1024];
    private int size;

    void write(byte[] bytes) {
      ensureCapacity(size + bytes.length);
      System.arraycopy(bytes, 0, data, size, bytes.length);
      size += bytes.length;
    }

    int size() {
      return size;
    }

    byte[] pop(int len) {
      byte[] out = new byte[len];
      System.arraycopy(data, 0, out, 0, len);
      int remaining = size - len;
      if (remaining > 0) {
        System.arraycopy(data, len, data, 0, remaining);
      }
      size = remaining;
      return out;
    }

    byte[] peek(int len) {
      byte[] out = new byte[len];
      System.arraycopy(data, 0, out, 0, len);
      return out;
    }

    int indexOf(byte[] needle) {
      outer:
      for (int i = 0; i <= size - needle.length; i++) {
        for (int j = 0; j < needle.length; j++) {
          if (data[i + j] != needle[j]) {
            continue outer;
          }
        }
        return i;
      }
      return -1;
    }

    void ensureCapacity(int capacity) {
      if (data.length >= capacity) {
        return;
      }
      int newCapacity = data.length;
      while (newCapacity < capacity) {
        newCapacity <<= 1;
      }
      byte[] next = new byte[newCapacity];
      System.arraycopy(data, 0, next, 0, size);
      data = next;
    }

    void clear() {
      size = 0;
    }
  }
}
