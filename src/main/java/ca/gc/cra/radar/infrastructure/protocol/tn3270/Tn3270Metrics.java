package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.MetricsPort;

final class Tn3270Metrics {
  private final MetricsPort metrics;

  Tn3270Metrics(MetricsPort metrics) {
    this.metrics = metrics;
  }

  void onBytes(int n) {
    metrics.observe("protocol.tn3270.bytes", n);
  }
}


