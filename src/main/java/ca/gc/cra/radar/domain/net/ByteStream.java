package ca.gc.cra.radar.domain.net;

/**
 * <strong>What:</strong> Ordered slice of TCP bytes emitted by a flow assembler for a single direction.
 * <p><strong>Why:</strong> Provides assemble-stage ports with immutable payload chunks plus flow metadata.</p>
 * <p><strong>Role:</strong> Domain value object handed from flow assembly to protocol reconstructors.</p>
 * <p><strong>Thread-safety:</strong> Immutable record; safe to share across threads.</p>
 * <p><strong>Performance:</strong> Holds a direct reference to the payload array; callers must respect ownership.</p>
 * <p><strong>Observability:</strong> Fields often map to metrics such as {@code assemble.byteStream.size}.</p>
 *
 * @param flow flow identifier for the segment; never {@code null}
 * @param fromClient {@code true} when the bytes originated from the client
 * @param data contiguous payload bytes; ownership is transferred to the {@link ByteStream}
 * @param timestampMicros timestamp associated with the latest contributing segment (microseconds)
 * @since 0.1.0
 */
public record ByteStream(FiveTuple flow, boolean fromClient, byte[] data, long timestampMicros) {
  /**
   * Creates a byte stream record without copying payload data.
   *
   * @implNote {@code data} is replaced with an empty array when {@code null} to simplify callers.
   *
   * <p><strong>Concurrency:</strong> Result is immutable; callers must not mutate {@code data} after passing it here.</p>
   * <p><strong>Performance:</strong> Avoids cloning by reusing the provided array.</p>
   * <p><strong>Observability:</strong> Timestamp is expected to be tagged on downstream metrics.</p>
   */
  public ByteStream {
    data = data != null ? data : new byte[0];
  }
}
