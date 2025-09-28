package ca.gc.cra.radar.domain.protocol.tn3270;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of a rendered TN3270 screen buffer.
 * <p>Contains decoded screen text and individual field metadata derived from the current session
 * state.</p>
 *
 * @since RADAR 0.2.0
 */
public final class ScreenSnapshot {
  private final int rows;
  private final int cols;
  private final String plainText;
  private final List<ScreenField> fields;

  /**
   * Creates a snapshot.
   *
   * @param rows terminal rows (e.g., 24)
   * @param cols terminal columns (e.g., 80)
   * @param plainText full-screen decoded text (newline separated); never {@code null}
   * @param fields immutable list of decoded screen fields; never {@code null}
   */
  public ScreenSnapshot(int rows, int cols, String plainText, List<ScreenField> fields) {
    if (rows <= 0) {
      throw new IllegalArgumentException("rows must be > 0 (was " + rows + ')');
    }
    if (cols <= 0) {
      throw new IllegalArgumentException("cols must be > 0 (was " + cols + ')');
    }
    this.rows = rows;
    this.cols = cols;
    this.plainText = Objects.requireNonNull(plainText, "plainText");
    this.fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
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
   * @return decoded screen text (newline separated)
   */
  public String plainText() {
    return plainText;
  }

  /**
   * @return immutable list of decoded screen fields
   */
  public List<ScreenField> fields() {
    return fields;
  }
}
