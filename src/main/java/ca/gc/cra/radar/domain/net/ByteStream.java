package ca.gc.cra.radar.domain.net;

/**
 * Represents an ordered slice of bytes delivered by the flow assembler for a particular direction
 * of a TCP connection.
 */
public record ByteStream(FiveTuple flow, boolean fromClient, byte[] data, long timestampMicros) {
  public ByteStream {
    data = data != null ? data.clone() : new byte[0];
  }
}


