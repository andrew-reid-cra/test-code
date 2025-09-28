package ca.gc.cra.radar.domain.protocol.tn3270;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mutable session state tracking the virtual 3270 screen buffer.
 *
 * <p><strong>Thread-safety:</strong> Not thread-safe. Each session must be owned by a single
 * assembler thread.</p>
 *
 * @since RADAR 0.2.0
 */
public final class Tn3270SessionState {
  /** Default screen rows. */
  public static final int DEFAULT_ROWS = 24;
  /** Default screen columns. */
  public static final int DEFAULT_COLS = 80;

  private final int rows;
  private final int cols;
  private final int size;
  private final byte[] buffer;
  private final ArrayList<FieldMeta> fields = new ArrayList<>(32);

  private int cursorAddress;
  private String lastScreenHash;

  /**
   * Creates a session state with the default 24x80 geometry.
   */
  public Tn3270SessionState() {
    this(DEFAULT_ROWS, DEFAULT_COLS);
  }

  /**
   * Creates a session state with custom geometry.
   *
   * @param rows screen rows (must be > 0)
   * @param cols screen columns (must be > 0)
   */
  public Tn3270SessionState(int rows, int cols) {
    if (rows <= 0) {
      throw new IllegalArgumentException("rows must be > 0 (was " + rows + ')');
    }
    if (cols <= 0) {
      throw new IllegalArgumentException("cols must be > 0 (was " + cols + ')');
    }
    this.rows = rows;
    this.cols = cols;
    this.size = rows * cols;
    this.buffer = new byte[size];
    clear();
  }

  /**
   * Clears the buffer to EBCDIC space and resets metadata.
   */
  public void clear() {
    for (int i = 0; i < buffer.length; i++) {
      buffer[i] = (byte) 0x40; // EBCDIC space
    }
    fields.clear();
    cursorAddress = 0;
  }

  /**
   * Clears field metadata while preserving screen data.
   */
  public void resetFields() {
    fields.clear();
  }

  /**
   * @return screen rows
   */
  public int rows() {
    return rows;
  }

  /**
   * @return screen columns
   */
  public int cols() {
    return cols;
  }

  /**
   * @return backing buffer (mutable, not thread-safe)
   */
  public byte[] buffer() {
    return buffer;
  }

  /**
   * Appends a start-field attribute into the field table.
   *
   * @param attributeAddress address where the attribute byte resides
   * @param attribute field attribute byte
   */
  public void startField(int attributeAddress, byte attribute) {
    int normalized = normalize(attributeAddress);
    FieldMeta meta = new FieldMeta(normalized, normalize(normalized + 1), attribute);
    if (!fields.isEmpty()) {
      FieldMeta previous = fields.get(fields.size() - 1);
      previous.setDataEnd(normalized);
    }
    fields.add(meta);
  }

  /**
   * Finalises the current field list after processing a host write.
   */
  public void finalizeFields() {
    if (fields.isEmpty()) {
      return;
    }
    FieldMeta last = fields.get(fields.size() - 1);
    last.setDataEnd(size);
  }

  /**
   * Returns an immutable view of the field table.
   *
   * @return field metadata
   */
  public List<FieldMeta> fields() {
    return Collections.unmodifiableList(fields);
  }

  /**
   * Locates the field covering the supplied address.
   *
   * @param address buffer address
   * @return matching field or {@code null}
   */
  public FieldMeta fieldForAddress(int address) {
    int normalized = normalize(address);
    for (FieldMeta meta : fields) {
      if (meta.contains(normalized)) {
        return meta;
      }
    }
    return null;
  }

  /**
   * Updates the cursor address (normalized to the buffer size).
   *
   * @param address cursor address
   */
  public void setCursorAddress(int address) {
    this.cursorAddress = normalize(address);
  }

  /**
   * @return cursor address within the screen buffer
   */
  public int cursorAddress() {
    return cursorAddress;
  }

  /**
   * @return last computed screen hash
   */
  public String lastScreenHash() {
    return lastScreenHash;
  }

  /**
   * Stores the latest screen hash.
   *
   * @param hash screen hash
   */
  public void lastScreenHash(String hash) {
    this.lastScreenHash = hash;
  }

  private int normalize(int address) {
    int normalized = address % size;
    return normalized < 0 ? normalized + size : normalized;
  }

  /** Field metadata maintained by the session state. */
  public static final class FieldMeta {
    private final int attributeAddress;
    private final int dataStart;
    private final byte attribute;
    private int dataEnd;
    private boolean modified;
    private String label;

    FieldMeta(int attributeAddress, int dataStart, byte attribute) {
      this.attributeAddress = attributeAddress;
      this.dataStart = dataStart;
      this.attribute = attribute;
      this.dataEnd = dataStart;
    }

    /**
     * @return attribute byte position
     */
    public int attributeAddress() {
      return attributeAddress;
    }

    /**
     * @return data start offset (excludes the attribute byte)
     */
    public int dataStart() {
      return dataStart;
    }

    /**
     * @return data end offset (exclusive)
     */
    public int dataEnd() {
      return dataEnd;
    }

    void setDataEnd(int dataEnd) {
      this.dataEnd = Math.max(dataStart, dataEnd);
    }

    /**
     * @return attribute byte value
     */
    public byte attribute() {
      return attribute;
    }

    /**
     * @return {@code true} when field is protected
     */
    public boolean isProtected() {
      return (attribute & 0x20) != 0;
    }

    /**
     * @return {@code true} when field is numeric-only
     */
    public boolean isNumeric() {
      return (attribute & 0x10) != 0;
    }

    /**
     * @return {@code true} when field is hidden/non-display
     */
    public boolean isHidden() {
      return (attribute & 0x0C) == 0x0C; // heuristic: both display bits set -> non-display
    }

    /**
     * @return {@code true} when terminal marked the field as modified
     */
    public boolean isModified() {
      return modified;
    }

    void setModified(boolean modified) {
      this.modified = modified;
    }

    /**
     * @return optional label derived from screen context
     */
    public String label() {
      return label;
    }

    void label(String label) {
      this.label = label;
    }

    /**
     * Returns {@code true} when the address falls within the field's data region.
     */
    public boolean contains(int address) {
      return address >= dataStart && address < dataEnd;
    }

    /**
     * @return field length in bytes
     */
    public int length() {
      return Math.max(0, dataEnd - dataStart);
    }
  }
}

