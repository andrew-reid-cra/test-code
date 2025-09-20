package ca.gc.cra.radar.infrastructure.metrics;

import ca.gc.cra.radar.application.port.MetricsPort;

public final class NoOpMetricsAdapter implements MetricsPort {
  @Override
  public void increment(String key) {}

  @Override
  public void observe(String key, long value) {}
}


