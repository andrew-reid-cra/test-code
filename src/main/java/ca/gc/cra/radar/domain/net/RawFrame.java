package ca.gc.cra.radar.domain.net;

/** Immutable representation of a captured link-layer frame. */
public record RawFrame(byte[] data, long timestampMicros) {
  public RawFrame {
    data = data != null ? data.clone() : new byte[0];
  }
}


