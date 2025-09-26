package ca.gc.cra.radar.infrastructure.metrics;

import ca.gc.cra.radar.application.port.MetricsPort;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics adapter that forwards RADAR counters and histograms to OpenTelemetry.
 */
public final class OpenTelemetryMetricsAdapter implements MetricsPort {
  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryMetricsAdapter.class);
  private static final AttributeKey<String> METRIC_KEY_ATTRIBUTE =
      AttributeKey.stringKey("radar.metric.key");
  private static final String FALLBACK_METRIC_NAME = "radar.metric";

  private final MetricsDelegate delegate;
  private final OpenTelemetryBootstrap.BootstrapResult bootstrap;

  /**
   * Creates an adapter wired to the environment-configured OpenTelemetry exporter.
   */
  public OpenTelemetryMetricsAdapter() {
    this(OpenTelemetryBootstrap.initialize());
  }

  OpenTelemetryMetricsAdapter(OpenTelemetryBootstrap.BootstrapResult bootstrap) {
    this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    if (bootstrap.isNoop()) {
      log.info("OpenTelemetry metrics adapter running in noop mode");
      this.delegate = NoopDelegate.INSTANCE;
    } else {
      this.delegate = new OtelDelegate(bootstrap.meter());
    }
  }

  @Override
  public void increment(String key) {
    delegate.increment(key);
  }

  @Override
  public void observe(String key, long value) {
    delegate.observe(key, value);
  }

  void forceFlush() {
    bootstrap.forceFlush();
  }

  void close() {
    bootstrap.close();
  }

  private interface MetricsDelegate {
    void increment(String key);

    void observe(String key, long value);
  }

  private static final class NoopDelegate implements MetricsDelegate {
    private static final NoopDelegate INSTANCE = new NoopDelegate();

    @Override
    public void increment(String key) {
      // no-op
    }

    @Override
    public void observe(String key, long value) {
      // no-op
    }
  }

  private static final class OtelDelegate implements MetricsDelegate {
    private final Meter meter;
    private final ConcurrentMap<String, CounterInstrument> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, HistogramInstrument> histograms = new ConcurrentHashMap<>();
    private final Map<String, String> sanitizedNames = new ConcurrentHashMap<>();

    private OtelDelegate(Meter meter) {
      this.meter = Objects.requireNonNull(meter, "meter");
    }

    @Override
    public void increment(String key) {
      String effectiveKey = Objects.requireNonNull(key, "key");
      CounterInstrument instrument = counters.computeIfAbsent(effectiveKey, this::createCounter);
      instrument.counter().add(1, instrument.attributes());
    }

    @Override
    public void observe(String key, long value) {
      String effectiveKey = Objects.requireNonNull(key, "key");
      HistogramInstrument instrument = histograms.computeIfAbsent(effectiveKey, this::createHistogram);
      instrument.histogram().record(value, instrument.attributes());
    }

    private CounterInstrument createCounter(String key) {
      String sanitized = sanitizedNames.computeIfAbsent(key, OtelDelegate::sanitizeName);
      LongCounter counter = meter
          .counterBuilder(sanitized)
          .setUnit("1")
          .setDescription("RADAR counter for " + key)
          .build();
      Attributes attributes = Attributes.of(METRIC_KEY_ATTRIBUTE, key);
      if (!sanitized.equals(key)) {
        log.debug("Sanitized counter name '{}' -> '{}'", key, sanitized);
      }
      return new CounterInstrument(counter, attributes);
    }

    private HistogramInstrument createHistogram(String key) {
      String sanitized = sanitizedNames.computeIfAbsent(key, OtelDelegate::sanitizeName);
      LongHistogram histogram = meter
          .histogramBuilder(sanitized)
          .ofLongs()
          .setDescription("RADAR observation for " + key)
          .build();
      Attributes attributes = Attributes.of(METRIC_KEY_ATTRIBUTE, key);
      if (!sanitized.equals(key)) {
        log.debug("Sanitized histogram name '{}' -> '{}'", key, sanitized);
      }
      return new HistogramInstrument(histogram, attributes);
    }

    private static String sanitizeName(String key) {
      if (key == null || key.isBlank()) {
        return FALLBACK_METRIC_NAME;
      }
      String trimmed = key.trim();
      String lower = trimmed.toLowerCase(Locale.ROOT);
      StringBuilder result = new StringBuilder(lower.length() + 4);
      char first = lower.charAt(0);
      if (!Character.isLetter(first)) {
        result.append('m');
      }
      for (int i = 0; i < lower.length(); i++) {
        char c = lower.charAt(i);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
          result.append(c);
        } else {
          result.append('_');
        }
      }
      if (result.length() == 0) {
        return FALLBACK_METRIC_NAME;
      }
      return result.toString();
    }
  }

  private record CounterInstrument(LongCounter counter, Attributes attributes) {}

  private record HistogramInstrument(LongHistogram histogram, Attributes attributes) {}
}
