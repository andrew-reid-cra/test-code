package ca.gc.cra.radar.infrastructure.net;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PayloadFlowAssemblerAdapterTest {
  private static final FiveTuple FLOW = new FiveTuple("10.0.0.1", 1000, "10.0.0.2", 2000, "TCP");

  @Test
  void nullSegmentIncrementsMetricAndReturnsEmpty() {
    RecordingMetricsPort metrics = new RecordingMetricsPort();
    PayloadFlowAssemblerAdapter adapter = new PayloadFlowAssemblerAdapter(metrics, "unit");

    assertTrue(adapter.accept(null).isEmpty());
    assertEquals(1, metrics.count("unit.nullSegment"));
  }

  @Test
  void emptyPayloadIncrementsEmptyMetric() {
    RecordingMetricsPort metrics = new RecordingMetricsPort();
    PayloadFlowAssemblerAdapter adapter = new PayloadFlowAssemblerAdapter(metrics, "unit");
    TcpSegment segment = new TcpSegment(FLOW, 1L, true, new byte[0], false, false, false, false, true, 1L);

    assertTrue(adapter.accept(segment).isEmpty());
    assertEquals(1, metrics.count("unit.emptyPayload"));
    assertFalse(metrics.hasCounter("unit.payload"));
  }

  @Test
  void nonEmptyPayloadProducesStreamAndRecordsMetrics() {
    RecordingMetricsPort metrics = new RecordingMetricsPort();
    PayloadFlowAssemblerAdapter adapter = new PayloadFlowAssemblerAdapter(metrics, "unit");
    TcpSegment segment = tcpSegment(true, "payload");
    byte[] original = segment.payload();

    Optional<ByteStream> result = adapter.accept(segment);
    assertTrue(result.isPresent());
    ByteStream stream = result.get();
    assertSame(original, stream.data());
    assertTrue(stream.fromClient());

    original[0] = 'X';
    assertEquals('X', stream.data()[0]);

    assertEquals(1, metrics.count("unit.payload"));
    assertEquals(List.of((long) stream.data().length), metrics.observed("unit.bytes"));
  }

  @Test
  void blankPrefixFallsBackToDefaultMetricKey() {
    RecordingMetricsPort metrics = new RecordingMetricsPort();
    PayloadFlowAssemblerAdapter adapter = new PayloadFlowAssemblerAdapter(metrics, " ");
    Optional<ByteStream> slice = adapter.accept(tcpSegment(false, "data"));
    assertTrue(slice.isPresent());

    assertEquals(1, metrics.count("flowAssembler.payload"));
    assertEquals(List.of(4L), metrics.observed("flowAssembler.bytes"));
  }

  private static TcpSegment tcpSegment(boolean fromClient, String data) {
    return new TcpSegment(
        FLOW,
        0L,
        fromClient,
        bytes(data),
        false,
        false,
        false,
        false,
        true,
        0L);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}

