package ca.gc.cra.radar.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryMetricsAdapterHistogramTest {
  private InMemoryMetricReader reader;
  private OpenTelemetryMetricsAdapter adapter;

  @BeforeEach
  void setUp() {
    reader = InMemoryMetricReader.create();
    OpenTelemetryBootstrap.BootstrapResult bootstrap = OpenTelemetryBootstrap.forTesting(reader);
    adapter = new OpenTelemetryMetricsAdapter(bootstrap);
  }

  @AfterEach
  void tearDown() {
    if (adapter != null) {
      adapter.close();
    }
  }

  @Test
  void observeRecordsHistogramSamples() {
    adapter.observe("live.persist.latencyNanos", 1_000L);
    adapter.observe("live.persist.latencyNanos", 2_000L);
    adapter.observe("live.persist.latencyNanos", 3_000L);
    adapter.forceFlush();

    Collection<MetricData> metrics = reader.collectAllMetrics();
    Optional<MetricData> maybeHistogram = metrics.stream()
        .filter(metric -> metric.getName().equals("live.persist.latencynanos"))
        .findFirst();
    assertTrue(maybeHistogram.isPresent(), "Expected histogram metric to be exported");

    MetricData histogram = maybeHistogram.orElseThrow();
    assertEquals(MetricDataType.HISTOGRAM, histogram.getType());
    HistogramPointData point = histogram.getHistogramData().getPoints().iterator().next();
    assertEquals(3L, point.getCount());
    assertEquals(6_000.0, point.getSum());
    AttributeKey<String> keyAttr = AttributeKey.stringKey("radar.metric.key");
    assertEquals("live.persist.latencyNanos", point.getAttributes().get(keyAttr));
  }
}
