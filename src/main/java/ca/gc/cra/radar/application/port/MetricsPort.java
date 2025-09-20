package ca.gc.cra.radar.application.port;

public interface MetricsPort {
  void increment(String key);

  void observe(String key, long value);

  MetricsPort NO_OP = new MetricsPort() {
    @Override public void increment(String key) {}
    @Override public void observe(String key, long value) {}
  };
}


