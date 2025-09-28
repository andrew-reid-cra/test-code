package ca.gc.cra.radar.domain.protocol.tn3270;

import java.util.HashMap;
import java.util.Map;

/**
 * 3270 Attention Identifier (AID) keys transmitted by terminals.
 * <p>The byte value precedes modified field data in client submissions. The mapping follows
 * IBM GA23-0059 and RFC 1576 guidance for TN3270.</p>
 *
 * @since RADAR 0.2.0
 */
public enum AidKey {
  /** Enter key (host program attention). */
  ENTER(0x7D),
  /** Clear screen and reset MDTs. */
  CLEAR(0x6D),
  /** Program Attention 1. */
  PA1(0x6C),
  /** Program Attention 2. */
  PA2(0x6B),
  /** Program Attention 3. */
  PA3(0x6A),
  /** Program Function 1. */
  PF1(0xF1),
  PF2(0xF2),
  PF3(0xF3),
  PF4(0xF4),
  PF5(0xF5),
  PF6(0xF6),
  PF7(0xF7),
  PF8(0xF8),
  PF9(0xF9),
  PF10(0xFA),
  PF11(0xFB),
  PF12(0xFC),
  PF13(0xC1),
  PF14(0xC2),
  PF15(0xC3),
  PF16(0xC4),
  PF17(0xC5),
  PF18(0xC6),
  PF19(0xC7),
  PF20(0xC8),
  PF21(0xC9),
  PF22(0xCA),
  PF23(0xCB),
  PF24(0xCC),
  /** Unrecognised AID value. */
  UNKNOWN(-1);

  private static final Map<Integer, AidKey> LOOKUP = buildLookup();

  private final int code;

  AidKey(int code) {
    this.code = code & 0xFF;
  }

  private static Map<Integer, AidKey> buildLookup() {
    Map<Integer, AidKey> map = new HashMap<>();
    for (AidKey key : values()) {
      if (key != UNKNOWN) {
        map.put(key.code, key);
      }
    }
    return map;
  }

  /**
   * Returns the raw AID byte value associated with the key.
   *
   * @return AID byte in the range {@code [0,255]}
   */
  public int code() {
    return code;
  }

  /**
   * Resolves an {@link AidKey} from the supplied raw byte.
   *
   * @param value raw byte (signed or unsigned) read from the TN3270 data stream
   * @return matching {@link AidKey} or {@link #UNKNOWN} when not recognised
   */
  public static AidKey fromByte(int value) {
    AidKey key = LOOKUP.get(value & 0xFF);
    return key == null ? UNKNOWN : key;
  }
}
