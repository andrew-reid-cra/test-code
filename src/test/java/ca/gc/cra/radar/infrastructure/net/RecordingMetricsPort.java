package ca.gc.cra.radar.infrastructure.net;

import ca.gc.cra.radar.application.port.MetricsPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double capturing metric usage for assertions.
 */
final class RecordingMetricsPort implements MetricsPort {
  private final Map<String, Integer> counters = new HashMap<>();
  private final Map<String, List<Long>> observations = new HashMap<>();

  @Override
  public void increment(String key) {
    counters.merge(key, 1, Integer::sum);
  }

  @Override
  public void observe(String key, long value) {
    observations.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
  }

  int count(String key) {
    return counters.getOrDefault(key, 0);
  }

  List<Long> observed(String key) {
    return observations.getOrDefault(key, Collections.emptyList());
  }

  boolean hasObservation(String key) {
    return observations.containsKey(key);
  }

  boolean hasCounter(String key) {
    return counters.containsKey(key);
  }

  void clear() {
    counters.clear();
    observations.clear();
  }
}
