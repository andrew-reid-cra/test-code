package ca.gc.cra.radar.infrastructure.protocol.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpFlowAssemblerAdapterTest {
  private static final FiveTuple FLOW = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");

  @Test
  void emitsByteStreamForNonEmptyPayload() {
    RecordingMetrics metrics = new RecordingMetrics();
    HttpFlowAssemblerAdapter adapter = new HttpFlowAssemblerAdapter(metrics);

    byte[] payload = new byte[] {1, 2, 3};
    TcpSegment segment =
        new TcpSegment(FLOW, 1L, true, payload, false, false, false, false, true, 42L);

    Optional<ByteStream> result = adapter.accept(segment);
    assertTrue(result.isPresent());
    ByteStream stream = result.get();
    assertTrue(stream.fromClient());
    assertArrayEquals(payload, stream.data());
    assertTrue(metrics.increments.containsKey("http.flowAssembler.payload"));
    assertEquals(metrics.observations.get("http.flowAssembler.bytes"), (Long) 3L);
  }

  @Test
  void ignoresEmptyPayload() {
    RecordingMetrics metrics = new RecordingMetrics();
    HttpFlowAssemblerAdapter adapter = new HttpFlowAssemblerAdapter(metrics);

    TcpSegment ackOnly =
        new TcpSegment(FLOW, 2L, false, new byte[0], false, false, false, false, true, 99L);

    Optional<ByteStream> result = adapter.accept(ackOnly);
    assertFalse(result.isPresent());
    assertTrue(metrics.increments.containsKey("http.flowAssembler.emptyPayload"));
  }

  private static final class RecordingMetrics implements MetricsPort {
    private final Map<String, Integer> increments = new HashMap<>();
    private final Map<String, Long> observations = new HashMap<>();

    @Override
    public void increment(String key) {
      increments.merge(key, 1, Integer::sum);
    }

    @Override
    public void observe(String key, long value) {
      observations.merge(key, value, Long::sum);
    }
  }
}

