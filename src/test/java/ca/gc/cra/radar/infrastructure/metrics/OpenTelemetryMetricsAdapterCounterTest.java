package ca.gc.cra.radar.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryMetricsAdapterCounterTest {
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
  void incrementRecordsCounterWithAttributes() {
    adapter.increment("capture.segment.persisted");
    adapter.increment("capture.segment.persisted");
    adapter.increment("capture.segment.persisted");
    adapter.forceFlush();

    Collection<MetricData> metrics = reader.collectAllMetrics();
    Optional<MetricData> maybeCounter = metrics.stream()
        .filter(metric -> metric.getName().equals("capture.segment.persisted"))
        .findFirst();
    assertTrue(maybeCounter.isPresent(), "Expected counter metric to be exported");

    MetricData counter = maybeCounter.orElseThrow();
    assertEquals(MetricDataType.LONG_SUM, counter.getType());

    LongPointData point = counter.getLongSumData().getPoints().iterator().next();
    assertEquals(3L, point.getValue());
    AttributeKey<String> keyAttr = AttributeKey.stringKey("radar.metric.key");
    assertEquals("capture.segment.persisted", point.getAttributes().get(keyAttr));

    AttributeKey<String> serviceName = AttributeKey.stringKey("service.name");
    assertEquals("radar", counter.getResource().getAttribute(serviceName));
    AttributeKey<String> serviceNamespace = AttributeKey.stringKey("service.namespace");
    assertEquals("ca.gc.cra", counter.getResource().getAttribute(serviceNamespace));
    AttributeKey<String> instanceId = AttributeKey.stringKey("service.instance.id");
    String instance = counter.getResource().getAttribute(instanceId);
    assertTrue(instance != null && !instance.isBlank(), "Service instance id should be provided");
  }
}
