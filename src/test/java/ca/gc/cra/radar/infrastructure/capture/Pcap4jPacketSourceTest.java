package ca.gc.cra.radar.infrastructure.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.net.RawFrame;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Pcap4jPacketSourceTest {
  private static final Path SMALL_PCAP = Path.of("src", "test", "resources", "pcaps", "small.pcap")
      .toAbsolutePath()
      .normalize();

  private InMemoryMetricReader metricReader;
  private InMemorySpanExporter spanExporter;
  private SdkMeterProvider meterProvider;
  private SdkTracerProvider tracerProvider;

  @BeforeEach
  void resetTelemetry() {
    metricReader = InMemoryMetricReader.create();
    spanExporter = InMemorySpanExporter.create();
    meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(metricReader)
        .build();
    tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
        .build();
    GlobalOpenTelemetry.resetForTest();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setMeterProvider(meterProvider)
        .setTracerProvider(tracerProvider)
        .build();
    GlobalOpenTelemetry.set(sdk);
  }

  @AfterEach
  void shutdownTelemetry() {
    if (tracerProvider != null) {
      CompletableResultCode shutdown = tracerProvider.shutdown();
      shutdown.join(5, TimeUnit.SECONDS);
    }
    if (meterProvider != null) {
      CompletableResultCode shutdown = meterProvider.shutdown();
      shutdown.join(5, TimeUnit.SECONDS);
    }
    GlobalOpenTelemetry.resetForTest();
    if (spanExporter != null) {
      spanExporter.reset();
    }
    if (metricReader != null) {
      metricReader.collectAllMetrics();
    }
  }

  @Test
  void offlinePcapDrainsAndReportsExhausted() throws Exception {
    assertTrue(Files.exists(SMALL_PCAP), "expected embedded pcap fixture");
    Pcap4jPacketSource source = new Pcap4jPacketSource(SMALL_PCAP, null, 65_535);

    source.start();
    assertFalse(source.isExhausted());

    int frameCount = 0;
    long byteCount = 0;
    Optional<RawFrame> maybeFrame;
    while ((maybeFrame = source.poll()).isPresent()) {
      RawFrame frame = maybeFrame.orElseThrow();
      frameCount++;
      byteCount += frame.data().length;
    }

    assertTrue(frameCount > 0, "expected frames to be replayed");
    assertTrue(byteCount > 0, "expected positive byte count");
    assertTrue(source.isExhausted());

    source.close();
  }

  @Test
  void closeIsIdempotent() throws Exception {
    Pcap4jPacketSource source = new Pcap4jPacketSource(SMALL_PCAP, null, 65_535);

    source.start();
    source.close();
    source.close();
  }

  @Test
  void emitsTelemetryCountersAndBytes() throws Exception {
    Pcap4jPacketSource source = new Pcap4jPacketSource(SMALL_PCAP, null, 65_535);
    source.start();

    int frames = 0;
    long bytes = 0;
    Optional<RawFrame> maybeFrame;
    while ((maybeFrame = source.poll()).isPresent()) {
      RawFrame frame = maybeFrame.orElseThrow();
      frames++;
      bytes += frame.data().length;
    }
    source.close();

    meterProvider.forceFlush().join(5, TimeUnit.SECONDS);
    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    MetricData packetMetric = findMetric(metrics, "radar.capture.packets.total");
    MetricData byteMetric = findMetric(metrics, "radar.capture.bytes.total");

    assertEquals(MetricDataType.LONG_SUM, packetMetric.getType());
    LongPointData packetPoint = packetMetric.getLongSumData().getPoints().iterator().next();
    assertEquals(frames, packetPoint.getValue());

    assertEquals(MetricDataType.LONG_SUM, byteMetric.getType());
    LongPointData bytePoint = byteMetric.getLongSumData().getPoints().iterator().next();
    assertEquals(bytes, bytePoint.getValue());

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertTrue(spans.stream().anyMatch(span -> span.getName().equals("capture.start")));
  }

  private MetricData findMetric(Collection<MetricData> metrics, String name) {
    return metrics.stream()
        .filter(metric -> metric.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Metric not found: " + name));
  }
}
