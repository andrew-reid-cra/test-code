package ca.gc.cra.radar.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryBootstrapNoopTest {
  private String previousExporter;

  @AfterEach
  void resetProperties() {
    if (previousExporter == null) {
      System.clearProperty("otel.metrics.exporter");
    } else {
      System.setProperty("otel.metrics.exporter", previousExporter);
    }
  }

  @Test
  void exporterNoneFallsBackToNoop() {
    previousExporter = System.getProperty("otel.metrics.exporter");
    System.setProperty("otel.metrics.exporter", "none");

    OpenTelemetryBootstrap.BootstrapResult result = OpenTelemetryBootstrap.initialize();
    assertTrue(result.isNoop(), "Expected noop metrics bootstrap when exporter=none");
    result.close();
  }
}
