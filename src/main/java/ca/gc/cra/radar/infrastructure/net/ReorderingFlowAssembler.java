package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Flow assembler that buffers TCP segments per direction and emits in-order byte streams.
 * <p>Synchronized to support concurrent ingestion per flow; buffering size depends on out-of-order
 * data retained until gaps close.
 *
 * @since RADAR 0.1-doc
 */
public final class ReorderingFlowAssembler implements FlowAssembler {
  private final MetricsPort metrics;
  private final String metricsPrefix;
  private final Map<DirectionKey, DirectionState> flows = new HashMap<>();

  /**
   * Builds a flow assembler with the provided metrics sink and key prefix.
   *
   * @param metrics metrics port used for instrumentation
   * @param metricsPrefix metric key prefix (defaults to flowAssembler when blank)
   */
  public ReorderingFlowAssembler(MetricsPort metrics, String metricsPrefix) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.metricsPrefix = (metricsPrefix == null || metricsPrefix.isBlank())
        ? "flowAssembler"
        : metricsPrefix;
  }

  /**
   * Consumes a TCP segment and emits an ordered byte stream slice when contiguous data is available.
   *
   * @param segment decoded segment to ingest; {@code null} increments a metric and yields empty
   * @return optional contiguous slice when the segment closes a gap
   */
  @Override
  public synchronized Optional<ByteStream> accept(TcpSegment segment) {
    if (segment == null) {
      metrics.increment(metricsPrefix + ".nullSegment");
      return Optional.empty();
    }
    DirectionKey key = new DirectionKey(segment.flow(), segment.fromClient());
    DirectionState state =
        flows.computeIfAbsent(
            key,
            k -> new DirectionState(metrics, metricsPrefix, segment.fromClient()));

    Optional<DirectionState.EmittedSlice> emitted = state.onSegment(segment);

    if (segment.fin() || segment.rst()) {
      state.markFinished();
    }
    if (state.shouldRetire()) {
      state.close();
      flows.remove(key);
    }

    return emitted.map(slice -> new ByteStream(
        segment.flow(),
        segment.fromClient(),
        // Defensive copy at the boundary to avoid exposing internal buffers
        slice.bytes().copy(),
        slice.timestampMicros()));
  }

  private record DirectionKey(FiveTuple flow, boolean fromClient) {}

  /**
   * Per-direction assembly state.
   */
  private static final class DirectionState {
    private final MetricsPort metrics;
    private final String metricsPrefix;
    private final NavigableMap<Long, Chunk> buffer = new TreeMap<>();
    private long nextExpected = Long.MIN_VALUE;
    private boolean finished;

    DirectionState(MetricsPort metrics, String metricsPrefix, boolean fromClient) {
      this.metrics = metrics;
      this.metricsPrefix = metricsPrefix + (fromClient ? ".client" : ".server");
    }

    Optional<EmittedSlice> onSegment(TcpSegment segment) {
      byte[] payload = segment.payload();
      if (payload.length > 0) {
        final long seq = normalize(segment.sequenceNumber());
        store(seq, payload, segment.timestampMicros());
      }

      Optional<EmittedSlice> emitted = emitIfReady();
      if (emitted.isPresent()) {
        metrics.increment(metricsPrefix + ".contiguous");
        metrics.observe(metricsPrefix + ".bytes", emitted.get().bytes().length());
      } else if (payload.length > 0) {
        metrics.increment(metricsPrefix + ".buffered");
      }
      return emitted;
    }

    /**
     * Insert a new segment payload into the buffer, trimming duplicates and overlaps.
     * This method is hot; all paths avoid unnecessary allocations.
     */
    private void store(long sequenceNumber, byte[] payload, long timestampMicros) {
      long start = sequenceNumber;

      // Discard if fully behind nextExpected
      if (hasNextExpected() && start + payload.length <= nextExpected) {
        metrics.increment(metricsPrefix + ".duplicate");
        return;
      }

      // Prefix-trim if partially behind nextExpected
      if (hasNextExpected() && start < nextExpected) {
        int skip = safeInt(nextExpected - start);
        if (skip >= payload.length) {
          metrics.increment(metricsPrefix + ".duplicate");
          return;
        }
        payload = trimLeft(payload, skip);
        start = nextExpected;
      }

      // Trim overlap with floor entry (left neighbor)
      Map.Entry<Long, Chunk> floor = buffer.floorEntry(start);
      if (floor != null) {
        long floorEnd = floor.getKey() + floor.getValue().bytes().length();
        if (floorEnd > start) {
          int overlap = safeInt(floorEnd - start);
          if (overlap >= payload.length) {
            metrics.increment(metricsPrefix + ".duplicate");
            return;
          }
          payload = trimLeft(payload, overlap);
          start += overlap;
        }
      }

      final long end = start + payload.length;

      // Remove/trim all overlapping right neighbors
      Map.Entry<Long, Chunk> overlapping = buffer.ceilingEntry(start);
      while (overlapping != null && overlapping.getKey() < end) {
        long existingStart = overlapping.getKey();
        Chunk existing = overlapping.getValue();
        long existingEnd = existingStart + existing.bytes().length();

        buffer.remove(existingStart);

        // If existing chunk extends past the new chunk's end, keep its tail
        if (existingEnd > end) {
          int tailLen = safeInt(existingEnd - end);
          byte[] tail = sliceRight(existing.bytes().raw(), tailLen);
          buffer.put(end, new Chunk(ImmutableBytes.ofOwned(tail), existing.timestampMicros()));
        }
        overlapping = buffer.ceilingEntry(start);
      }

      // Store new chunk (one defensive copy on ingress)
      buffer.put(start, new Chunk(ImmutableBytes.of(payload), timestampMicros));
      metrics.increment(metricsPrefix + ".stored");
    }

    private Optional<EmittedSlice> emitIfReady() {
      if (buffer.isEmpty()) {
        return Optional.empty();
      }

      long expected = hasNextExpected() ? nextExpected : buffer.firstKey();

      if (buffer.firstKey() > expected) {
        nextExpected = expected;
        return Optional.empty();
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      long cursor = expected;
      long lastTimestamp = 0L;

      while (!buffer.isEmpty()) {
        Map.Entry<Long, Chunk> entry = buffer.firstEntry();
        long start = entry.getKey();
        if (start > cursor) {
          break; // gap
        }
        Chunk chunk = entry.getValue();
        buffer.pollFirstEntry();

        byte[] data = chunk.bytes().raw();
        if (start < cursor) {
          int skip = safeInt(cursor - start);
          if (skip >= data.length) {
            continue;
          }
          data = trimLeft(data, skip);
          start = cursor;
        }

        out.write(data, 0, data.length);
        cursor = start + data.length;
        lastTimestamp = chunk.timestampMicros();
      }

      if (out.size() == 0) {
        nextExpected = cursor;
        return Optional.empty();
      }

      nextExpected = cursor;
      return Optional.of(new EmittedSlice(ImmutableBytes.ofOwned(out.toByteArray()), lastTimestamp));
    }

    void markFinished() { finished = true; }
    boolean shouldRetire() { return finished && buffer.isEmpty(); }
    void close() { buffer.clear(); }
    private boolean hasNextExpected() { return nextExpected != Long.MIN_VALUE; }
    private long normalize(long sequenceNumber) { return sequenceNumber & 0xFFFFFFFFL; }

    // ---------- small, allocation-light helpers ----------

    /** Trim left by {@code n} bytes; returns a new array view (copy) of the remainder. */
    private static byte[] trimLeft(byte[] src, int n) {
      byte[] trimmed = new byte[src.length - n];
      System.arraycopy(src, n, trimmed, 0, trimmed.length);
      return trimmed;
    }

    /** Take the last {@code len} bytes from {@code src}. */
    private static byte[] sliceRight(byte[] src, int len) {
      byte[] out = new byte[len];
      System.arraycopy(src, src.length - len, out, 0, len);
      return out;
    }

    /** Safe narrowing from long to int for positive, bounded differences. */
    private static int safeInt(long v) {
      return (v > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) v;
    }

    // ---------- immutable value types (no duplication) ----------

    /**
     * Immutable, deep-equality byte container.
     * One defensive copy is performed on creation. Internal array can be exposed to
     * callers within this enclosing class via {@link #raw()} without further copies.
     */
    private static final class ImmutableBytes {
      private final byte[] data;

      private ImmutableBytes(byte[] owned) { // assumes caller will not reuse 'owned'
        this.data = owned;
      }

      /** Copies the source for safety. Use when data originates outside this class. */
      static ImmutableBytes of(byte[] src) {
        return new ImmutableBytes(src == null ? new byte[0] : src.clone());
      }

      /** Wraps an already-copied buffer without cloning again. */
      static ImmutableBytes ofOwned(byte[] alreadyOwned) {
        return new ImmutableBytes(alreadyOwned == null ? new byte[0] : alreadyOwned);
      }

      /** Length without copying. */
      int length() { return data.length; }

      /** Raw access for internal use (never mutate!). */
      byte[] raw() { return data; }

      /** Defensive copy for outbound boundaries. */
      byte[] copy() { return data.clone(); }

      @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImmutableBytes other)) return false;
        return Arrays.equals(this.data, other.data);
      }

      @Override public int hashCode() { return Arrays.hashCode(data); }

      @Override public String toString() { return Arrays.toString(data); }
    }

    /** Stored buffered chunk. */
    private record Chunk(ImmutableBytes bytes, long timestampMicros) {
      Chunk {
        // Normalize null
        if (bytes == null) bytes = ImmutableBytes.ofOwned(new byte[0]);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Chunk other)) {
          return false;
        }
        return timestampMicros == other.timestampMicros && bytes.equals(other.bytes);
      }

      @Override
      public int hashCode() {
        return Objects.hash(bytes, timestampMicros);
      }
    }

    /** Emitted contiguous slice. */
    private record EmittedSlice(ImmutableBytes bytes, long timestampMicros) {
      EmittedSlice {
        if (bytes == null) bytes = ImmutableBytes.ofOwned(new byte[0]);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof EmittedSlice other)) {
          return false;
        }
        return timestampMicros == other.timestampMicros && bytes.equals(other.bytes);
      }

      @Override
      public int hashCode() {
        return Objects.hash(bytes, timestampMicros);
      }
    }
  }
}
