package ca.gc.cra.radar.domain.protocol.tn3270;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable session state tracking the virtual 3270 screen buffer across partitions.
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

  private static final byte[] EMPTY_ATTRIBUTES = new byte[0];

  private final int defaultRows;
  private final int defaultCols;
  private final Map<Integer, PartitionState> partitions = new LinkedHashMap<>();
  private PartitionState active;

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
    this.defaultRows = rows;
    this.defaultCols = cols;
    PartitionState initial = new PartitionState(0, rows, cols);
    partitions.put(0, initial);
    this.active = initial;
  }

  /**
   * Selects an existing partition as the active context, creating it if absent.
   *
   * @param partitionId partition identifier (0-255 typical)
   */
  public void selectPartition(int partitionId) {
    active = partition(partitionId);
  }

  /**
   * Configures (optionally clearing) a partition and makes it active.
   *
   * @param partitionId partition identifier
   * @param rows row count (<=0 retains previous dimension)
   * @param cols column count (<=0 retains previous dimension)
   * @param clear whether to clear the partition contents
   */
  public void configurePartition(int partitionId, int rows, int cols, boolean clear) {
    configurePartition(partitionId, rows, cols, clear, 0, EMPTY_ATTRIBUTES);
  }

  /**
   * Configures (optionally clearing) a partition with attribute metadata and makes it active.
   *
   * @param partitionId partition identifier
   * @param rows row count (<=0 retains previous dimension)
   * @param cols column count (<=0 retains previous dimension)
   * @param clear whether to clear the partition contents
   * @param flags partition attribute flags (0-255)
   * @param attributes optional attribute bytes (may be {@code null})
   */
  public void configurePartition(
      int partitionId, int rows, int cols, boolean clear, int flags, byte[] attributes) {
    PartitionState partition = partition(partitionId);
    partition.configureGeometry(rows > 0 ? rows : partition.rows,
        cols > 0 ? cols : partition.cols,
        clear);
    partition.applyAttributes(flags, attributes);
    active = partition;
  }

  /**
   * @return currently active partition identifier
   */
  public int activePartitionId() {
    return active.id;
  }

  /**
   * Clears the active partition to EBCDIC space and removes metadata.
   */
  public void clear() {
    active.clear();
  }

  /**
   * Clears field metadata while preserving screen data for the active partition.
   */
  public void resetFields() {
    active.resetFields();
  }

  /**
   * @return rows for the active partition
   */
  public int rows() {
    return active.rows;
  }

  /**
   * @return columns for the active partition
   */
  public int cols() {
    return active.cols;
  }

  /**
   * @return backing buffer for the active partition
   */
  public byte[] buffer() {
    return active.buffer;
  }

  /**
   * @return partition attribute flags for the active partition
   */
  public int partitionFlags() {
    return active.flags();
  }

  /**
   * Returns a defensive copy of the active partition attribute bytes.
   *
   * @return attribute byte array (possibly empty)
   */
  public byte[] partitionAttributes() {
    return active.attributesCopy();
  }

  /**
   * Appends a start-field attribute into the active partition field table.
   *
   * @param attributeAddress address where the attribute byte resides
   * @param attribute field attribute byte
   */
  public void startField(int attributeAddress, byte attribute) {
    active.startField(attributeAddress, attribute);
  }

  /**
   * Finalises the current field list after host processing for the active partition.
   */
  public void finalizeFields() {
    active.finalizeFields();
  }

  /**
   * Returns an immutable view of the active partition field table.
   *
   * @return field metadata list
   */
  public List<FieldMeta> fields() {
    return active.fieldsView();
  }

  /**
   * Locates the field covering the supplied address within the active partition.
   *
   * @param address buffer address
   * @return matching field or {@code null}
   */
  public FieldMeta fieldForAddress(int address) {
    return active.fieldForAddress(address);
  }

  /**
   * Returns an immutable snapshot of the requested partition state.
   *
   * @param partitionId partition identifier
   * @return partition snapshot
   * @throws IllegalArgumentException when the partition is unknown
   */
  public PartitionSnapshot partitionSnapshot(int partitionId) {
    PartitionState partition = partitions.get(partitionId);
    if (partition == null) {
      throw new IllegalArgumentException("unknown partition " + partitionId);
    }
    return partition.snapshot();
  }

  /**
   * Updates the cursor address for the active partition.
   *
   * @param address cursor address
   */
  public void setCursorAddress(int address) {
    active.setCursorAddress(address);
  }

  /**
   * @return cursor address within the active partition buffer
   */
  public int cursorAddress() {
    return active.cursorAddress;
  }

  /**
   * @return last computed screen hash for the active partition
   */
  public String lastScreenHash() {
    return active.lastScreenHash;
  }

  /**
   * Stores the latest screen hash for the active partition.
   *
   * @param hash screen hash
   */
  public void lastScreenHash(String hash) {
    active.lastScreenHash = hash;
  }

  /**
   * Normalises an address within the active partition.
   *
   * @param address raw address
   * @return normalised address
   */
  public int normalize(int address) {
    return active.normalize(address);
  }

  private PartitionState partition(int partitionId) {
    return partitions.computeIfAbsent(partitionId, id -> new PartitionState(id, defaultRows, defaultCols));
  }

  private static final class PartitionState {
    private final int id;
    private int rows;
    private int cols;
    private int size;
    private byte[] buffer;
    private final ArrayList<FieldMeta> fields = new ArrayList<>(32);
    private int flags;
    private byte[] attributes = EMPTY_ATTRIBUTES;
    private int cursorAddress;
    private String lastScreenHash;

    PartitionState(int id, int rows, int cols) {
      this.id = id;
      configureGeometry(rows, cols, true);
      applyAttributes(0, EMPTY_ATTRIBUTES);
    }

    void configureGeometry(int rows, int cols, boolean clear) {
      boolean geometryChanged = this.rows != rows || this.cols != cols || buffer == null;
      this.rows = rows;
      this.cols = cols;
      this.size = rows * cols;
      if (geometryChanged) {
        buffer = new byte[size];
        fillSpace();
        fields.clear();
        cursorAddress = 0;
        lastScreenHash = null;
      } else if (clear) {
        clear();
      } else {
        fields.clear();
      }
    }

    void clear() {
      fillSpace();
      fields.clear();
      cursorAddress = 0;
      lastScreenHash = null;
    }

    void resetFields() {
      fields.clear();
    }

    void applyAttributes(int flags, byte[] attributeBytes) {
      this.flags = flags & 0xFF;
      if (attributeBytes == null || attributeBytes.length == 0) {
        this.attributes = EMPTY_ATTRIBUTES;
      } else {
        this.attributes = attributeBytes.clone();
      }
    }

    void startField(int attributeAddress, byte attribute) {
      int normalised = normalize(attributeAddress);
      FieldMeta meta = new FieldMeta(id, normalised, normalize(normalised + 1), attribute);
      if (!fields.isEmpty()) {
        fields.get(fields.size() - 1).setDataEnd(normalised);
      }
      fields.add(meta);
      buffer[normalised] = attribute;
    }

    void finalizeFields() {
      if (fields.isEmpty()) {
        return;
      }
      fields.get(fields.size() - 1).setDataEnd(size);
    }

    List<FieldMeta> fieldsView() {
      return Collections.unmodifiableList(fields);
    }

    FieldMeta fieldForAddress(int address) {
      int normalised = normalize(address);
      for (FieldMeta meta : fields) {
        if (meta.contains(normalised)) {
          return meta;
        }
      }
      return null;
    }

    void setCursorAddress(int address) {
      this.cursorAddress = normalize(address);
    }

    int flags() {
      return flags;
    }

    byte[] attributesCopy() {
      return attributes == EMPTY_ATTRIBUTES ? EMPTY_ATTRIBUTES : attributes.clone();
    }

    PartitionSnapshot snapshot() {
      byte[] bufferCopy = buffer.clone();
      byte[] attributeCopy = attributes == EMPTY_ATTRIBUTES ? EMPTY_ATTRIBUTES : attributes.clone();
      return new PartitionSnapshot(
          id,
          rows,
          cols,
          cursorAddress,
          flags,
          attributeCopy,
          bufferCopy,
          List.copyOf(fields),
          lastScreenHash);
    }

    int normalize(int address) {
      if (size == 0) {
        return 0;
      }
      int normalised = address % size;
      return normalised < 0 ? normalised + size : normalised;
    }

    private void fillSpace() {
      Arrays.fill(buffer, (byte) 0x40);
    }
  }

  /** Field metadata maintained by the session state. */
  public static final class FieldMeta {
    private final int partitionId;
    private final int attributeAddress;
    private final int dataStart;
    private final byte attribute;
    private int dataEnd;
    private boolean modified;
    private String label;

    FieldMeta(int partitionId, int attributeAddress, int dataStart, byte attribute) {
      this.partitionId = partitionId;
      this.attributeAddress = attributeAddress;
      this.dataStart = dataStart;
      this.attribute = attribute;
      this.dataEnd = dataStart;
    }

    /**
     * @return partition containing the field
     */
    public int partitionId() {
      return partitionId;
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
      return (attribute & 0x0C) == 0x0C;
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

  /** Immutable snapshot of a partition within the session state. */
  public record PartitionSnapshot(
      int id,
      int rows,
      int cols,
      int cursorAddress,
      int flags,
      byte[] attributes,
      byte[] buffer,
      List<FieldMeta> fields,
      String lastScreenHash) {
    /**
     * Creates a snapshot and defensively copies mutable data.
     */
    public PartitionSnapshot {
      attributes = attributes != null ? attributes.clone() : new byte[0];
      buffer = buffer != null ? buffer.clone() : new byte[0];
      fields = fields != null ? List.copyOf(fields) : List.of();
    }

    /**
     * Returns a defensive copy of the partition attribute bytes.
     *
     * @return attribute bytes copied from the session buffer
     */
    public byte[] attributes() {
      return attributes.clone();
    }

    /**
     * Returns a defensive copy of the partition screen buffer.
     *
     * @return screen buffer bytes copied from the session buffer
     */
    public byte[] buffer() {
      return buffer.clone();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || o.getClass() != getClass()) {
        return false;
      }
      PartitionSnapshot that = (PartitionSnapshot) o;
      return id == that.id
          && rows == that.rows
          && cols == that.cols
          && cursorAddress == that.cursorAddress
          && flags == that.flags
          && Arrays.equals(attributes, that.attributes)
          && Arrays.equals(buffer, that.buffer)
          && Objects.equals(fields, that.fields)
          && Objects.equals(lastScreenHash, that.lastScreenHash);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(id, rows, cols, cursorAddress, flags, fields, lastScreenHash);
      result = 31 * result + Arrays.hashCode(attributes);
      result = 31 * result + Arrays.hashCode(buffer);
      return result;
    }

    @Override
    public String toString() {
      return "PartitionSnapshot{"
          + "id=" + id
          + ", rows=" + rows
          + ", cols=" + cols
          + ", cursorAddress=" + cursorAddress
          + ", flags=" + flags
          + ", attributes=" + Arrays.toString(attributes)
          + ", buffer=" + Arrays.toString(buffer)
          + ", fields=" + fields
          + ", lastScreenHash='" + lastScreenHash + '\''
          + '}';
    }
  }
}





