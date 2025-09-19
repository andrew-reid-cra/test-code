package sniffer.domain.tn3270;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;

final class Tn3270Screen {
  static final int CAPACITY = 4096;
  private static final int BUFFER_SIZE = CAPACITY; // accommodates all common 3270 models
  private static final int DEFAULT_COLS = 80;
  private static final int DEFAULT_ROWS = 24;
  private static final Charset EBCDIC = Charset.forName("Cp037");
  private static final char[] EBCDIC_TABLE = buildEbcdicTable();

  private final char[] cells = new char[BUFFER_SIZE];
  private final byte[] fieldAttr = new byte[BUFFER_SIZE];

  private int displayCols = DEFAULT_COLS;
  private int displayRows = DEFAULT_ROWS;
  private int maxAddrTouched = 0;

  Tn3270Screen() {
    clear();
  }

  void clear() {
    Arrays.fill(cells, ' ');
    Arrays.fill(fieldAttr, (byte) 0);
    maxAddrTouched = 0;
  }

  void setDimensions(int rows, int cols) {
    if (rows <= 0 || cols <= 0) return;
    displayRows = Math.min(rows, BUFFER_SIZE / cols);
    displayCols = cols;
  }

  int normalizeAddr(int addr) {
    int mod = BUFFER_SIZE;
    addr %= mod;
    if (addr < 0) addr += mod;
    return addr;
  }

  void markField(int addr, int attr) {
    // Field attribute byte (FA) at current address; not displayed directly.
    addr = normalizeAddr(addr);
    fieldAttr[addr] = (byte) attr;
    cells[addr] = ' ';
    maxAddrTouched = Math.max(maxAddrTouched, addr);
  }

  void writeChar(int addr, int ebcdic) {
    addr = normalizeAddr(addr);
    cells[addr] = toAscii(ebcdic);
    maxAddrTouched = Math.max(maxAddrTouched, addr);
  }

  void repeatTo(int start, int target, int ebcdic) {
    start = normalizeAddr(start);
    target = normalizeAddr(target);
    int pos = start;
    int guard = 0;
    char ch = toAscii(ebcdic);
    while (pos != target && guard < BUFFER_SIZE) {
      cells[pos] = ch;
      maxAddrTouched = Math.max(maxAddrTouched, pos);
      pos = (pos + 1) % BUFFER_SIZE;
      guard++;
    }
  }

  void eraseTo(int start, int target) {
    start = normalizeAddr(start);
    target = normalizeAddr(target);
    int pos = start;
    int guard = 0;
    while (pos != target && guard < BUFFER_SIZE) {
      cells[pos] = ' ';
      pos = (pos + 1) % BUFFER_SIZE;
      guard++;
    }
  }

  int nextPosition(int addr) {
    return (normalizeAddr(addr) + 1) % BUFFER_SIZE;
  }

  boolean isFieldAttribute(int addr) {
    return fieldAttr[normalizeAddr(addr)] != 0;
  }

  boolean isFieldUnprotected(int addr) {
    int attr = fieldAttr[normalizeAddr(addr)] & 0xFF;
    return attr != 0 && (attr & 0x20) == 0; // bit 5 == 1 means protected
  }

  int capacity() {
    return BUFFER_SIZE;
  }

  String render() {
    int rows = Math.min(displayRows, BUFFER_SIZE / displayCols);
    StringBuilder sb = new StringBuilder(rows * (displayCols + 1));
    for (int r = 0; r < rows; r++) {
      int start = r * displayCols;
      int end = Math.min(start + displayCols, BUFFER_SIZE);
      int last = end - 1;
      while (last >= start && cells[last] == ' ') last--;
      if (last < start) {
        sb.append('
');
      } else {
        sb.append(cells, start, last - start + 1).append('
');
      }
    }
    return sb.toString();
  }

  private static char[] buildEbcdicTable() {
    char[] map = new char[256];
    Charset cs = EBCDIC;
    for (int i = 0; i < 256; i++) {
      byte[] b = { (byte) i };
      char c = cs.decode(ByteBuffer.wrap(b)).charAt(0);
      if (c < 0x20 && !Character.isWhitespace(c)) {
        c = ' ';
      }
      map[i] = c;
    }
    return map;
  }

  private static char toAscii(int ebcdic) {
    return EBCDIC_TABLE[ebcdic & 0xFF];
  }
}



