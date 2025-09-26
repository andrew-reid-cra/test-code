package ca.gc.cra.radar.infrastructure.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstraps the OpenTelemetry meter provider according to RADAR conventions.
 */
final class OpenTelemetryBootstrap {
  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryBootstrap.class);
  private static final String INSTRUMENTATION_SCOPE = "ca.gc.cra.radar";
  private static final String DEFAULT_EXPORTER = "otlp";
  private static final String DEFAULT_ENDPOINT = "http://localhost:4317";
  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
  private static final AttributeKey<String> SERVICE_NAMESPACE = AttributeKey.stringKey("service.namespace");
  private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey("service.version");
  private static final AttributeKey<String> SERVICE_INSTANCE_ID = AttributeKey.stringKey("service.instance.id");

  private OpenTelemetryBootstrap() {
    // Utility class
  }

  static BootstrapResult initialize() {
    try {
      BootstrapConfig config = BootstrapConfig.fromEnvironment();
      if (config.exporter() == ExporterMode.NONE) {
        log.info("OpenTelemetry metrics exporter disabled (exporter=none)");
        return BootstrapResult.noop();
      }
      return buildActive(config);
    } catch (RuntimeException ex) {
      log.error("Failed to initialize OpenTelemetry metrics; using noop adapter", ex);
      return BootstrapResult.noop();
    }
  }

  static BootstrapResult forTesting(MetricReader reader) {
    Objects.requireNonNull(reader, "reader");
    String version = detectServiceVersion();
    Resource resource = buildResource(version, Attributes.empty());
    SdkMeterProvider provider = SdkMeterProvider.builder()
        .setResource(resource)
        .registerMetricReader(reader)
        .build();
    Meter meter = provider.meterBuilder(INSTRUMENTATION_SCOPE)
        .setInstrumentationVersion(version)
        .build();
    return BootstrapResult.active(provider, meter);
  }

  private static BootstrapResult buildActive(BootstrapConfig config) {
    SdkMeterProviderBuilder builder = SdkMeterProvider.builder().setResource(config.resource());
    MetricReader reader = createReader(config);
    if (reader != null) {
      builder.registerMetricReader(reader);
    }
    SdkMeterProvider provider = builder.build();
    Meter meter = provider.meterBuilder(INSTRUMENTATION_SCOPE)
        .setInstrumentationVersion(config.instrumentationVersion())
        .build();
    log.info(
        "OpenTelemetry metrics initialized with exporter {} targeting {}",
        config.exporter(),
        config.endpoint());
    return BootstrapResult.active(provider, meter);
  }

  private static MetricReader createReader(BootstrapConfig config) {
    if (config.exporter() == ExporterMode.OTLP) {
      Duration interval = Duration.ofSeconds(30);
      OtlpGrpcMetricExporter exporter =
          OtlpGrpcMetricExporter.builder().setEndpoint(config.endpoint()).build();
      return PeriodicMetricReader.builder(exporter).setInterval(interval).build();
    }
    return null;
  }

  private static Resource buildResource(String version, Attributes additional) {
    AttributesBuilder builder = Attributes.builder()
        .put(SERVICE_NAME, "radar")
        .put(SERVICE_NAMESPACE, "ca.gc.cra")
        .put(SERVICE_VERSION, version);
    String instanceId = detectInstanceId();
    if (!instanceId.isBlank()) {
      builder.put(SERVICE_INSTANCE_ID, instanceId);
    }
    Resource base = Resource.create(builder.build());
    Resource extra = additional.isEmpty() ? Resource.empty() : Resource.create(additional);
    return Resource.getDefault().merge(base).merge(extra);
  }

  private static Attributes parseResourceAttributes(String raw) {
    if (raw == null || raw.isBlank()) {
      return Attributes.empty();
    }
    AttributesBuilder builder = Attributes.builder();
    for (String token : raw.split(",")) {
      String trimmed = token.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int idx = trimmed.indexOf('=');
      if (idx <= 0 || idx == trimmed.length() - 1) {
        log.warn("Ignoring malformed OTEL_RESOURCE_ATTRIBUTES entry: {}", trimmed);
        continue;
      }
      String key = trimmed.substring(0, idx).trim();
      String value = trimmed.substring(idx + 1).trim();
      if (key.isEmpty() || value.isEmpty()) {
        log.warn("Ignoring OTEL_RESOURCE_ATTRIBUTES entry with blank key/value: {}", trimmed);
        continue;
      }
      builder.put(AttributeKey.stringKey(key), value);
    }
    return builder.build();
  }

  private static String detectInstanceId() {
    String envOverride = System.getenv("OTEL_RESOURCE_SERVICE_INSTANCE");
    if (envOverride != null && !envOverride.isBlank()) {
      return envOverride.trim();
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ex) {
      log.debug("Falling back to runtime MXBean for instance id", ex);
      String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
      return runtimeName != null ? runtimeName : "unknown";
    }
  }

  private static String detectServiceVersion() {
    Package pkg = OpenTelemetryBootstrap.class.getPackage();
    if (pkg != null) {
      String impl = pkg.getImplementationVersion();
      if (impl != null && !impl.isBlank()) {
        return impl;
      }
    }
    try (InputStream in = OpenTelemetryBootstrap.class.getResourceAsStream(
        "/META-INF/maven/ca.gc.cra/RADAR/pom.properties")) {
      if (in != null) {
        Properties props = new Properties();
        props.load(in);
        String version = props.getProperty("version");
        if (version != null && !version.isBlank()) {
          return version;
        }
      }
    } catch (IOException ex) {
      log.debug("Unable to read pom.properties for version detection", ex);
    }
    return "0.0.0-dev";
  }

  private static BootstrapConfig buildConfigFromEnvironment() {
    Properties props = System.getProperties();
    String exporterValue = firstNonBlank(
        props.getProperty("otel.metrics.exporter"),
        System.getenv("OTEL_METRICS_EXPORTER"),
        DEFAULT_EXPORTER);
    ExporterMode exporter = ExporterMode.from(exporterValue);
    String endpoint = firstNonBlank(
        props.getProperty("otel.exporter.otlp.endpoint"),
        System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
        DEFAULT_ENDPOINT);
    String resourceAttrs = firstNonBlank(
        props.getProperty("otel.resource.attributes"),
        System.getenv("OTEL_RESOURCE_ATTRIBUTES"),
        "");
    String version = detectServiceVersion();
    Attributes extras = parseResourceAttributes(resourceAttrs);
    Resource resource = buildResource(version, extras);
    return new BootstrapConfig(exporter, endpoint, resource, version);
  }

  private static String firstNonBlank(String first, String second, String defaultValue) {
    if (first != null && !first.isBlank()) {
      return first.trim();
    }
    if (second != null && !second.isBlank()) {
      return second.trim();
    }
    return defaultValue;
  }

  static final class BootstrapConfig {
    private final ExporterMode exporter;
    private final String endpoint;
    private final Resource resource;
    private final String instrumentationVersion;

    private BootstrapConfig(
        ExporterMode exporter,
        String endpoint,
        Resource resource,
        String instrumentationVersion) {
      this.exporter = exporter;
      this.endpoint = endpoint;
      this.resource = resource;
      this.instrumentationVersion = instrumentationVersion;
    }

    static BootstrapConfig fromEnvironment() {
      return buildConfigFromEnvironment();
    }

    ExporterMode exporter() {
      return exporter;
    }

    String endpoint() {
      return endpoint;
    }

    Resource resource() {
      return resource;
    }

    String instrumentationVersion() {
      return instrumentationVersion;
    }
  }

  enum ExporterMode {
    OTLP,
    NONE;

    static ExporterMode from(String raw) {
      if (raw == null || raw.isBlank()) {
        return OTLP;
      }
      String normalized = raw.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "none" -> NONE;
        case "otlp" -> OTLP;
        default -> {
          log.warn("Unknown OTEL_METRICS_EXPORTER value '{}'; defaulting to {}", raw, DEFAULT_EXPORTER);
          yield OTLP;
        }
      };
    }
  }

  static final class BootstrapResult implements AutoCloseable {
    private final Meter meter;
    private final SdkMeterProvider provider;
    private final boolean noop;

    private BootstrapResult(Meter meter, SdkMeterProvider provider, boolean noop) {
      this.meter = meter;
      this.provider = provider;
      this.noop = noop;
    }

    static BootstrapResult noop() {
      MeterProvider provider = MeterProvider.noop();
      Meter meter = provider.get(INSTRUMENTATION_SCOPE);
      return new BootstrapResult(meter, null, true);
    }

    static BootstrapResult active(SdkMeterProvider provider, Meter meter) {
      return new BootstrapResult(meter, provider, false);
    }

    Meter meter() {
      return meter;
    }

    boolean isNoop() {
      return noop;
    }

    void forceFlush() {
      if (provider == null) {
        return;
      }
      CompletableResultCode result = provider.forceFlush();
      result.join(5, TimeUnit.SECONDS);
      if (!result.isSuccess()) {
        log.warn("OpenTelemetry metrics flush did not complete within timeout");
      }
    }

    @Override
    public void close() {
      if (provider == null) {
        return;
      }
      try {
        CompletableResultCode shutdown = provider.shutdown();
        shutdown.join(5, TimeUnit.SECONDS);
        if (!shutdown.isSuccess()) {
          log.warn("Timed out waiting for OpenTelemetry meter provider shutdown");
        }
      } catch (RuntimeException ex) {
        log.warn("Failed to close OpenTelemetry meter provider cleanly", ex);
      }
    }
  }
}
