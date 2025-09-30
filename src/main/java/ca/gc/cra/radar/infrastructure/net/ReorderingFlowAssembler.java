package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.io.ByteArrayOutputStream;
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
   * Creates a reordering assembler.
   *
   * @param metrics metrics sink for assembler statistics
   * @param metricsPrefix prefix used for metric keys; defaults to {@code "flowAssembler"}
   * @throws NullPointerException if {@code metrics} is {@code null}
   * @since RADAR 0.1-doc
   */
  public ReorderingFlowAssembler(MetricsPort metrics, String metricsPrefix) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.metricsPrefix = (metricsPrefix == null || metricsPrefix.isBlank())
        ? "flowAssembler"
        : metricsPrefix;
  }

  /**
   * Accepts a TCP segment, buffering as needed until contiguous bytes become available.
   *
   * @param segment segment to assemble; may be {@code null}
   * @return byte stream slice when contiguous bytes are ready; otherwise empty
   * @implNote Maintains per-direction buffers and trims duplicates to honour TCP semantics.
   * @since RADAR 0.1-doc
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
        slice.data(),
        slice.timestampMicros()));
  }

  private record DirectionKey(FiveTuple flow, boolean fromClient) {}

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
        long seq = normalize(segment.sequenceNumber());
        store(seq, payload, segment.timestampMicros());
      }

      Optional<EmittedSlice> emitted = emitIfReady();
      if (emitted.isPresent()) {
        metrics.increment(metricsPrefix + ".contiguous");
        metrics.observe(metricsPrefix + ".bytes", emitted.get().data().length);
      } else if (payload.length > 0) {
        metrics.increment(metricsPrefix + ".buffered");
      }
      return emitted;
    }

    private void store(long sequenceNumber, byte[] payload, long timestampMicros) {
      long start = sequenceNumber;
      if (nextExpected != Long.MIN_VALUE && start + payload.length <= nextExpected) {
        metrics.increment(metricsPrefix + ".duplicate");
        return;
      }
      if (nextExpected != Long.MIN_VALUE && start < nextExpected) {
        int skip = (int) Math.min(Integer.MAX_VALUE, nextExpected - start);
        if (skip >= payload.length) {
          metrics.increment(metricsPrefix + ".duplicate");
          return;
        }
        byte[] trimmed = new byte[payload.length - skip];
        System.arraycopy(payload, skip, trimmed, 0, trimmed.length);
        payload = trimmed;
        start = nextExpected;
      }

      Map.Entry<Long, Chunk> floor = buffer.floorEntry(start);
      if (floor != null) {
        long floorEnd = floor.getKey() + floor.getValue().data().length;
        if (floorEnd > start) {
          int overlap = (int) Math.min(Integer.MAX_VALUE, floorEnd - start);
          if (overlap >= payload.length) {
            metrics.increment(metricsPrefix + ".duplicate");
            return;
          }
          byte[] trimmed = new byte[payload.length - overlap];
          System.arraycopy(payload, overlap, trimmed, 0, trimmed.length);
          payload = trimmed;
          start += overlap;
        }
      }

      long end = start + payload.length;
      Map.Entry<Long, Chunk> overlapping = buffer.ceilingEntry(start);
      while (overlapping != null && overlapping.getKey() < end) {
        long existingStart = overlapping.getKey();
        Chunk existing = overlapping.getValue();
        long existingEnd = existingStart + existing.data().length;
        buffer.remove(existingStart);
        if (existingEnd > end) {
          int tailLen = (int) Math.min(Integer.MAX_VALUE, existingEnd - end);
          byte[] tail = new byte[tailLen];
          System.arraycopy(existing.data(), existing.data().length - tailLen, tail, 0, tailLen);
          buffer.put(end, new Chunk(tail, existing.timestampMicros()));
        }
        overlapping = buffer.ceilingEntry(start);
      }

      buffer.put(start, new Chunk(payload.clone(), timestampMicros));
      metrics.increment(metricsPrefix + ".stored");
    }

    private Optional<EmittedSlice> emitIfReady() {
      if (buffer.isEmpty()) {
        return Optional.empty();
      }
      long expected = nextExpected;
      if (expected == Long.MIN_VALUE) {
        expected = buffer.firstKey();
      }

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
          break;
        }
        Chunk chunk = entry.getValue();
        buffer.pollFirstEntry();
        byte[] data = chunk.data();
        if (start < cursor) {
          int skip = (int) Math.min(Integer.MAX_VALUE, cursor - start);
          if (skip >= data.length) {
            continue;
          }
          byte[] trimmed = new byte[data.length - skip];
          System.arraycopy(data, skip, trimmed, 0, trimmed.length);
          data = trimmed;
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
      return Optional.of(new EmittedSlice(out.toByteArray(), lastTimestamp));
    }

    void markFinished() {
      finished = true;
    }

    boolean shouldRetire() {
      return finished && buffer.isEmpty();
    }

    void close() {
      buffer.clear();
    }

    private long normalize(long sequenceNumber) {
      return sequenceNumber & 0xFFFFFFFFL;
    }

    private record Chunk(byte[] data, long timestampMicros) {
      Chunk {
        data = data != null ? data.clone() : new byte[0];
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Chunk chunk)) {
          return false;
        }
        return timestampMicros == chunk.timestampMicros
            && Arrays.equals(data, chunk.data);
      }

      @Override
      public int hashCode() {
        int result = Arrays.hashCode(data);
        result = 31 * result + Long.hashCode(timestampMicros);
        return result;
      }

      @Override
      public String toString() {
        return "Chunk{data=" + Arrays.toString(data)
            + ", timestampMicros=" + timestampMicros
            + '}';
      }
    }

    record EmittedSlice(byte[] data, long timestampMicros) {
      EmittedSlice {
        data = data != null ? data.clone() : new byte[0];
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof EmittedSlice slice)) {
          return false;
        }
        return timestampMicros == slice.timestampMicros
            && Arrays.equals(data, slice.data);
      }

      @Override
      public int hashCode() {
        int result = Arrays.hashCode(data);
        result = 31 * result + Long.hashCode(timestampMicros);
        return result;
      }

      @Override
      public String toString() {
        return "EmittedSlice{data=" + Arrays.toString(data)
            + ", timestampMicros=" + timestampMicros
            + '}';
      }
    }
  }
}

