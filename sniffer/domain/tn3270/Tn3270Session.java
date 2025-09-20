package ca.gc.cra.radar.infrastructure.protocol.http.legacy.tn3270;

import ca.gc.cra.radar.infrastructure.protocol.http.legacy.HttpIds;
import ca.gc.cra.radar.infrastructure.protocol.http.legacy.SegmentSink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

final class Tn3270Session {
  private final SegmentSink sink;
  private final ArrayDeque<String> pending = new ArrayDeque<>();
  private final Tn3270Screen screen = new Tn3270Screen();

  Tn3270Session(SegmentSink sink) {
    this.sink = sink;
  }

  void onServerRecord(long tsMicros, String src, int sport, String dst, int dport, byte[] record) {
    if (record.length < 2) return;
    int cmd = record[0] & 0xFF;
    if (cmd != 0xF3 && cmd != 0xF5) {
      // Not a Write/EraseWrite; ignore negotiations like Read/WSF for now.
      return;
    }
    int pos = 1;
    int wcc = pos < record.length ? (record[pos++] & 0xFF) : 0;
    if (cmd == 0xF3) {
      screen.clear();
    }

    applyOrders(record, pos);

    String id = HttpIds.ulid();
    pending.addLast(id);
    emit(tsMicros, id, "TN3270_RSP", src, sport, dst, dport,
        "command=%s wcc=%02X bytes=%d".formatted(cmdName(cmd), wcc, record.length));
  }

  void onClientRecord(long tsMicros, String src, int sport, String dst, int dport, byte[] record) {
    if (record.length < 3) return;
    int aid = record[0] & 0xFF;
    int cursor = bufferAddress(record[1], record[2]);
    // Terminal responses may only contain modified fields; apply updates to current screen.
    applyOrders(record, 3);

    String id = pending.isEmpty() ? HttpIds.ulid() : pending.pollFirst();
    emit(tsMicros, id, "TN3270_REQ", src, sport, dst, dport,
        "aid=%s cursor=%03d bytes=%d".formatted(aidName(aid), cursor, record.length));
  }

  private void emit(long tsMicros, String id, String kind,
                    String src, int sport, String dst, int dport, String headerLine) {
    String srcKey = src + ":" + sport;
    String dstKey = dst + ":" + dport;
    SegmentSink.Meta meta = new SegmentSink.Meta(id, kind, null, srcKey, dstKey, tsMicros, tsMicros);
    byte[] headers = ("TN3270\n" + headerLine + "\n").getBytes(StandardCharsets.UTF_8);
    SegmentSink.StreamHandle handle = sink.begin(meta, headers);
    String bodyText = screen.render();
    byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
    sink.append(handle, body, 0, body.length);
    sink.end(handle, tsMicros);
  }

  private void applyOrders(byte[] buf, int start) {
    int cursor = 0;
    int pos = start;
    while (pos < buf.length) {
      int b = buf[pos++] & 0xFF;
      switch (b) {
        case 0x11 -> { // SBA
          if (pos + 1 >= buf.length) return;
          cursor = bufferAddress(buf[pos], buf[pos + 1]);
          pos += 2;
        }
        case 0x1D -> { // SF
          if (pos >= buf.length) return;
          screen.markField(cursor, buf[pos++] & 0xFF);
          cursor = screen.nextPosition(cursor);
        }
        case 0x3C -> { // RA
          if (pos + 2 >= buf.length) return;
          int target = bufferAddress(buf[pos], buf[pos + 1]);
          int fill = buf[pos + 2] & 0xFF;
          pos += 3;
          screen.repeatTo(cursor, target, fill);
          cursor = target;
        }
        case 0x12 -> { // EUA
          if (pos + 1 >= buf.length) return;
          int target = bufferAddress(buf[pos], buf[pos + 1]);
          pos += 2;
          screen.eraseTo(cursor, target);
          cursor = target;
        }
        case 0x05 -> { cursor = advanceToNextField(cursor); }
        case 0x13 -> { /* IC - ignore */ }
        case 0x29 -> { // SFE
          if (pos >= buf.length) return;
          int count = buf[pos++] & 0xFF;
          int bytes = count * 2;
          if (pos + bytes > buf.length) return;
          pos += bytes;
          screen.markField(cursor, 0);
          cursor = screen.nextPosition(cursor);
        }
        case 0x28 -> { // SA
          if (pos + 1 >= buf.length) return;
          pos += 2;
        }
        case 0x2C -> { // MF
          if (pos >= buf.length) return;
          int mfLen = buf[pos++] & 0xFF;
          if (pos + mfLen > buf.length) return;
          pos += mfLen;
        }
        default -> {
          screen.writeChar(cursor, b);
          cursor = screen.nextPosition(cursor);
        }
      }
    }
  }

  private int advanceToNextField(int cursor) {
    int pos = screen.normalizeAddr(cursor);
    for (int i = 0; i < screen.capacity(); i++) {
      pos = screen.nextPosition(pos);
      if (screen.isFieldAttribute(pos) && screen.isFieldUnprotected(pos)) {
        return screen.nextPosition(pos);
      }
    }
    return cursor;
  }

  private static int bufferAddress(int high, int low) {
    return ((high & 0x3F) << 6) | (low & 0x3F);
  }

  private static String cmdName(int cmd) {
    return switch (cmd) {
      case 0xF3 -> "ERASE_WRITE";
      case 0xF5 -> "WRITE";
      default -> "CMD_" + Integer.toHexString(cmd);
    };
  }

  private static String aidName(int aid) {
    return switch (aid & 0xFF) {
      case 0x88 -> "AID_ENTER";
      case 0xF1 -> "AID_PF1";
      case 0xF2 -> "AID_PF2";
      case 0xF3 -> "AID_PF3";
      case 0xF4 -> "AID_PF4";
      case 0xF5 -> "AID_PF5";
      case 0xF6 -> "AID_PF6";
      case 0xF7 -> "AID_PF7";
      case 0xF8 -> "AID_PF8";
      case 0xF9 -> "AID_PF9";
      case 0x7D -> "AID_FIELDMARK";
      default -> "AID_" + Integer.toHexString(aid & 0xFF);
    };
  }
}



