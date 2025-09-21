package ca.gc.cra.radar.infrastructure.net;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReorderingFlowAssemblerTest {
  private static final FiveTuple FLOW = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
  private RecordingMetricsPort metrics;
  private ReorderingFlowAssembler assembler;

  @BeforeEach
  void setUp() {
    metrics = new RecordingMetricsPort();
    assembler = new ReorderingFlowAssembler(metrics, "test");
  }

  @Test
  void acceptNullSegmentRecordsMetric() {
    assertTrue(assembler.accept(null).isEmpty());
    assertEquals(1, metrics.count("test.nullSegment"));
  }

  @Test
  void assemblesOutOfOrderSegmentsAndRecordsMetrics() {
    Optional<ByteStream> first = assembler.accept(segment(1_000, true, "foo"));
    assertTrue(first.isPresent());
    assertArrayEquals(bytes("foo"), first.get().data());

    Optional<ByteStream> gap = assembler.accept(segment(1_006, true, "bar"));
    assertTrue(gap.isEmpty());

    Optional<ByteStream> merged = assembler.accept(segment(1_003, true, "baz"));
    assertTrue(merged.isPresent());
    assertArrayEquals(bytes("bazbar"), merged.get().data());

    assertEquals(3, metrics.count("test.client.stored"));
    assertEquals(1, metrics.count("test.client.buffered"));
    assertEquals(2, metrics.count("test.client.contiguous"));
    assertEquals(List.of(3L, 6L), metrics.observed("test.client.bytes"));
  }

  @Test
  void handlesServerDirectionAndUsesServerMetrics() {
    Optional<ByteStream> slice = assembler.accept(segment(2_000, false, "abc"));
    assertTrue(slice.isPresent());
    assertEquals(1, metrics.count("test.server.stored"));
    assertEquals(1, metrics.count("test.server.contiguous"));
    assertEquals(List.of(3L), metrics.observed("test.server.bytes"));
  }

  @Test
  void zeroLengthPayloadIgnored() {
    TcpSegment empty = new TcpSegment(FLOW, 5L, true, new byte[0], false, false, false, false, true, 5L);
    assertTrue(assembler.accept(empty).isEmpty());
    assertFalse(metrics.hasCounter("test.client.stored"));
    assertFalse(metrics.hasCounter("test.client.contiguous"));
  }

  @Test
  void trimsDuplicatesAndCountsMetrics() {
    Optional<ByteStream> first = assembler.accept(segment(3_000, true, "hello"));
    assertTrue(first.isPresent());

    Optional<ByteStream> overlap = assembler.accept(segment(3_003, true, "lo world"));
    assertTrue(overlap.isPresent());
    assertArrayEquals(bytes(" world"), overlap.get().data());

    Optional<ByteStream> extended = assembler.accept(segment(3_003, true, "lo world!!!"));
    assertTrue(extended.isPresent());
    assertArrayEquals(bytes("!!!"), extended.get().data());

    Optional<ByteStream> duplicate = assembler.accept(segment(3_003, true, "lo world!!!"));
    assertTrue(duplicate.isEmpty());

    assertEquals(3, metrics.count("test.client.stored"));
    assertEquals(1, metrics.count("test.client.duplicate"));
    assertEquals(List.of(5L, 6L, 3L), metrics.observed("test.client.bytes"));
  }

  @Test
  void finSegmentRetiresFlowAfterEmission() throws Exception {
    assembler.accept(segment(4_000, true, "abc"));
    Optional<ByteStream> finSlice = assembler.accept(segmentWithFlags(4_003, true, "def", true, false));
    assertTrue(finSlice.isPresent());
    assertEquals(0, flowCount());
  }

  @Test
  void rstWithoutPayloadRetiresFlow() throws Exception {
    assembler.accept(segment(5_000, true, "abc"));
    TcpSegment rst = segmentWithFlags(5_003, true, new byte[0], false, true);
    assertTrue(assembler.accept(rst).isEmpty());
    assertEquals(0, flowCount());
  }

  @Test
  void sequenceWrapAroundTreatsOldDataAsDuplicate() {
    assembler.accept(segment(0xFFFF_FFF0L, true, "abcd"));
    Optional<ByteStream> wrapped = assembler.accept(segment(5L, true, "efg"));
    assertTrue(wrapped.isEmpty());
    assertEquals(1, metrics.count("test.client.duplicate"));
  }

  @Test
  void completedFlowRemovesInternalState() throws Exception {
    assembler.accept(segment(6_000, true, "abc"));
    assembler.accept(segmentWithFlags(6_003, true, "def", true, false));
    assembler.accept(segment(6_006, true, "ghi"));
    Optional<ByteStream> second = assembler.accept(segmentWithFlags(6_009, true, "jkl", true, false));
    assertTrue(second.isPresent());
    assertEquals(0, flowCount());
  }

  private int flowCount() throws Exception {
    Field field = ReorderingFlowAssembler.class.getDeclaredField("flows");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<?, ?> flows = (Map<?, ?>) field.get(assembler);
    return flows.size();
  }

  private static TcpSegment segment(long seq, boolean fromClient, String data) {
    return segment(seq, fromClient, bytes(data));
  }

  private static TcpSegment segment(long seq, boolean fromClient, byte[] payload) {
    return segmentWithFlags(seq, fromClient, payload, false, false);
  }

  private static TcpSegment segmentWithFlags(
      long seq,
      boolean fromClient,
      String data,
      boolean fin,
      boolean rst) {
    return segmentWithFlags(seq, fromClient, bytes(data), fin, rst);
  }

  private static TcpSegment segmentWithFlags(
      long seq,
      boolean fromClient,
      byte[] payload,
      boolean fin,
      boolean rst) {
    return new TcpSegment(
        FLOW,
        seq,
        fromClient,
        payload,
        fin,
        false,
        rst,
        false,
        true,
        seq);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
