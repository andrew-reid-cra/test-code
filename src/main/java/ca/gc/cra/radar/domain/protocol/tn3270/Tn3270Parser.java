package ca.gc.cra.radar.domain.protocol.tn3270;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless TN3270 parser that interprets host writes and client submissions.
 *
 * @since RADAR 0.2.0
 */
public final class Tn3270Parser {
  private static final Charset EBCDIC = Charset.forName("Cp1047");
  private static final byte EBCDIC_SPACE = (byte) 0x40;

  private static final int CMD_WRITE = 0xF1;
  private static final int CMD_ERASE_WRITE = 0xF5;
  private static final int CMD_WRITE_STRUCTURED_FIELD = 0xF3;

  private static final int ORDER_SBA = 0x11;
  private static final int ORDER_EUA = 0x12;
  private static final int ORDER_IC = 0x13;
  private static final int ORDER_SF = 0x1D;

  private static final int SFID_READ_BUFFER = 0x02;
  private static final int SFID_READ_PARTITION = 0x04;
  private static final int SFID_QUERY_REPLY = 0x81;

  private Tn3270Parser() {}

  /**
   * Parses a host write buffer, mutating the supplied session state and producing a snapshot.
   *
   * @param payload telnet-buffered payload stripped of IAC sequences
   * @param state mutable session state
   * @return decoded screen snapshot
   */
  public static ScreenSnapshot parseHostWrite(ByteBuffer payload, Tn3270SessionState state) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(state, "state");

    ByteBuffer data = payload.duplicate();
    data.clear();
    if (!data.hasRemaining()) {
      return buildSnapshot(state);
    }

    int command = data.get() & 0xFF;
    if (command != CMD_WRITE && command != CMD_ERASE_WRITE && command != CMD_WRITE_STRUCTURED_FIELD) {
      return buildSnapshot(state);
    }

    if (!data.hasRemaining()) {
      return buildSnapshot(state);
    }

    int wcc = data.get() & 0xFF;
    if (command == CMD_WRITE_STRUCTURED_FIELD) {
      handleStructuredFields(data, state, wcc);
    } else {
      if (command == CMD_ERASE_WRITE || (wcc & 0x40) != 0) {
        state.clear();
      } else {
        state.resetFields();
      }
      processOrders(data, state, 0);
    }

    state.finalizeFields();
    deriveLabels(state);
    ScreenSnapshot snapshot = buildSnapshot(state);
    String hash = computeScreenHash(snapshot);
    state.lastScreenHash(hash);
    return snapshot;
  }

  private static void handleStructuredFields(ByteBuffer data, Tn3270SessionState state, int wcc) {
    if ((wcc & 0x40) != 0) {
      state.clear();
    }
    while (data.remaining() >= 3) {
      int lengthHigh = data.get() & 0xFF;
      int lengthLow = data.get() & 0xFF;
      int length = (lengthHigh << 8) | lengthLow;
      if (length < 3 || length - 2 > data.remaining()) {
        data.position(data.limit());
        break;
      }
      int sfid = data.get() & 0xFF;
      int payloadLength = length - 3;
      ByteBuffer payload = data.slice();
      payload.limit(payloadLength);
      data.position(data.position() + payloadLength);
      switch (sfid) {
        case SFID_READ_BUFFER -> processReadBuffer(payload, state);
        case SFID_READ_PARTITION -> processReadPartition(payload, state);
        case SFID_QUERY_REPLY -> processQueryReply(payload, state);
        default -> {
          // TODO: add support for additional structured fields (e.g., Bind Image, Read MDT).
        }
      }
    }
  }

  private static void processReadBuffer(ByteBuffer payload, Tn3270SessionState state) {
    processStructuredField(payload, state, true);
  }

  private static void processReadPartition(ByteBuffer payload, Tn3270SessionState state) {
    processStructuredField(payload, state, false);
  }

  private static void processQueryReply(ByteBuffer payload, Tn3270SessionState state) {
    if (payload.remaining() < 3) {
      return;
    }
    int partitionId = payload.get() & 0xFF;
    int rows = payload.get() & 0xFF;
    int cols = payload.get() & 0xFF;
    state.configurePartition(partitionId, rows, cols, false, 0, null);
    state.resetFields();
  }

  private static void processStructuredField(
      ByteBuffer payload, Tn3270SessionState state, boolean defaultClear) {
    if (payload.remaining() < 4) {
      return;
    }
    int partitionId = payload.get() & 0xFF;
    int flags = payload.get() & 0xFF;
    int rows = payload.get() & 0xFF;
    int cols = payload.get() & 0xFF;

    boolean clear = defaultClear || (flags & 0x40) != 0;
    state.configurePartition(partitionId, rows, cols, clear, flags, null);
    state.resetFields();

    if (!payload.hasRemaining()) {
      return;
    }
    processOrders(payload, state, 0);
  }

  /**
   * Parses a client submission (AID + modified fields).
   *
   * @param payload terminal submission payload
   * @param state session state used to resolve fields
   * @return parsed submit result
   */
  public static SubmitResult parseClientSubmit(ByteBuffer payload, Tn3270SessionState state) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(state, "state");

    ByteBuffer data = payload.duplicate();
    data.clear();
    if (!data.hasRemaining()) {
      return new SubmitResult(AidKey.UNKNOWN, Map.of());
    }

    int aidByte = data.get() & 0xFF;
    AidKey aid = AidKey.fromByte(aidByte);

    if (data.remaining() >= 2) {
      int cursor = decodeAddress(data.get() & 0xFF, data.get() & 0xFF);
      state.setCursorAddress(cursor);
    }

    Map<String, String> inputs = new LinkedHashMap<>();
    while (data.hasRemaining()) {
      int order = data.get() & 0xFF;
      if (order == ORDER_SBA) {
        if (data.remaining() < 2) {
          break;
        }
        int address = decodeAddress(data.get() & 0xFF, data.get() & 0xFF);
        byte[] fieldBytes = readFieldBytes(data);
        if (fieldBytes.length == 0) {
          continue;
        }
        Tn3270SessionState.FieldMeta meta = state.fieldForAddress(address);
        if (meta == null || meta.isProtected() || meta.isHidden()) {
          continue;
        }
        meta.setModified(true);
        String value = rtrim(decode(fieldBytes));
        if (value.isEmpty()) {
          continue;
        }
        String key = deriveFieldKey(meta, address);
        inputs.put(key, value);
      } else if (order == ORDER_IC) {
        if (data.remaining() >= 2) {
          int cursor = decodeAddress(data.get() & 0xFF, data.get() & 0xFF);
          state.setCursorAddress(cursor);
        }
      } else {
        // TODO: Support additional orders such as START FIELD EXTENDED or FORMAT WRITE
      }
    }

    return new SubmitResult(aid, Map.copyOf(inputs));
  }

  /** Submit result bundling AID and input map. */
  public record SubmitResult(AidKey aid, Map<String, String> inputs) {}

  private static void processOrders(ByteBuffer data, Tn3270SessionState state, int startAddress) {
    int address = startAddress;
    while (data.hasRemaining()) {
      int next = data.get() & 0xFF;
      switch (next) {
        case ORDER_SBA -> {
          if (data.remaining() < 2) {
            data.position(data.limit());
            return;
          }
          address = decodeAddress(data.get() & 0xFF, data.get() & 0xFF);
        }
        case ORDER_SF -> {
          if (!data.hasRemaining()) {
            return;
          }
          byte attribute = data.get();
          state.startField(address, attribute);
          state.buffer()[state.normalize(address)] = attribute;
          address = advance(address, state);
        }
        case ORDER_IC -> state.setCursorAddress(address);
        case ORDER_EUA -> {
          if (data.remaining() < 2) {
            return;
          }
          int target = decodeAddress(data.get() & 0xFF, data.get() & 0xFF);
          clearRegion(state, address, target);
          address = target;
        }
        default -> {
          state.buffer()[state.normalize(address)] = (byte) next;
          address = advance(address, state);
        }
      }
    }
  }

  private static int advance(int address, Tn3270SessionState state) {
    return state.normalize(address + 1);
  }

  private static void clearRegion(Tn3270SessionState state, int start, int target) {
    int current = state.normalize(start);
    int end = state.normalize(target);
    int size = state.rows() * state.cols();
    if (start == target || size == 0) {
      return;
    }
    int steps = (end >= current) ? end - current : (size - current) + end;
    byte[] buffer = state.buffer();
    for (int i = 0; i < steps; i++) {
      buffer[state.normalize(current + i)] = EBCDIC_SPACE;
    }
  }

  private static int decodeAddress(int high, int low) {
    return ((high & 0x3F) << 6) | (low & 0x3F);
  }

  private static ScreenSnapshot buildSnapshot(Tn3270SessionState state) {
    List<ScreenField> fields = new ArrayList<>();
    for (Tn3270SessionState.FieldMeta meta : state.fields()) {
      int length = meta.length();
      if (length <= 0) {
        continue;
      }
      byte[] buffer = state.buffer();
      String value = rtrim(new String(buffer, meta.dataStart(), length, EBCDIC));
      fields.add(new ScreenField(
          meta.dataStart(),
          length,
          meta.isProtected(),
          meta.isNumeric(),
          meta.isHidden(),
          value));
    }

    String plainText = renderPlain(state);
    return new ScreenSnapshot(state.rows(), state.cols(), plainText, fields);
  }

  private static String renderPlain(Tn3270SessionState state) {
    StringBuilder sb = new StringBuilder(state.rows() * (state.cols() + 1));
    byte[] buffer = state.buffer();
    int cols = state.cols();
    for (int row = 0; row < state.rows(); row++) {
      int offset = row * cols;
      int length = Math.min(cols, buffer.length - offset);
      if (length <= 0) {
        break;
      }
      String line = new String(buffer, offset, length, EBCDIC);
      sb.append(rtrim(line));
      if (row < state.rows() - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  private static void deriveLabels(Tn3270SessionState state) {
    byte[] buffer = state.buffer();
    int cols = state.cols();
    for (Tn3270SessionState.FieldMeta meta : state.fields()) {
      if (meta.isProtected()) {
        continue;
      }
      int pos = meta.attributeAddress() - 1;
      if (pos < 0) {
        continue;
      }
      int rowStart = (pos / cols) * cols;
      StringBuilder sb = new StringBuilder();
      boolean seen = false;
      while (pos >= rowStart) {
        byte value = buffer[pos];
        if (value == EBCDIC_SPACE) {
          if (seen) {
            break;
          }
          pos--;
          continue;
        }
        if ((value & 0xFF) < 0x40) {
          break;
        }
        sb.append(new String(new byte[] {value}, EBCDIC));
        seen = true;
        pos--;
      }
      if (sb.length() > 0) {
        meta.label(rtrim(sb.reverse().toString()));
      }
    }
  }

  private static byte[] readFieldBytes(ByteBuffer data) {
    int start = data.position();
    while (data.hasRemaining()) {
      int peek = data.get(data.position()) & 0xFF;
      if (peek == ORDER_SBA || peek == ORDER_EUA || peek == ORDER_IC) {
        break;
      }
      data.position(data.position() + 1);
    }
    int end = data.position();
    int length = end - start;
    if (length <= 0) {
      return new byte[0];
    }
    byte[] out = new byte[length];
    data.position(start);
    data.get(out);
    return out;
  }

  private static String decode(byte[] data) {
    byte[] copy = data.clone();
    for (int i = 0; i < copy.length; i++) {
      if (copy[i] == 0) {
        copy[i] = EBCDIC_SPACE;
      }
    }
    return new String(copy, EBCDIC);
  }

  private static String deriveFieldKey(Tn3270SessionState.FieldMeta meta, int address) {
    String label = meta.label();
    if (label != null && !label.isBlank()) {
      return label;
    }
    return String.format(Locale.ROOT, "field_%d", address);
  }

  private static String rtrim(String value) {
    int end = value.length();
    while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }

  private static String computeScreenHash(ScreenSnapshot snapshot) {
    StringBuilder sb = new StringBuilder();
    for (ScreenField field : snapshot.fields()) {
      if (field.protectedField()) {
        sb.append(field.value());
        sb.append('|');
      }
    }
    if (sb.length() == 0) {
      return null;
    }
    return Tn3270Hashes.murmur128Hex(sb.toString());
  }
}

