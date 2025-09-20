package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.MetricsPort;

final class HttpMetrics {
  private final MetricsPort metrics;

  HttpMetrics(MetricsPort metrics) {
    this.metrics = metrics;
  }

  void onBytes(int n) {
    metrics.observe("protocol.http.bytes", n);
  }
}


