package ca.gc.cra.radar.api;

import ca.gc.cra.radar.validation.Strings;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies telemetry-related CLI settings to the active JVM for OpenTelemetry bootstrapping.
 */
final class TelemetryConfigurator {
  private static final Logger log = LoggerFactory.getLogger(TelemetryConfigurator.class);
  private static final int MAX_RESOURCE_ATTRIBUTES_LENGTH = 4_096;

  private TelemetryConfigurator() {}

  static void configureMetrics(Map<String, String> args) {
    if (args == null || args.isEmpty()) {
      return;
    }
    String exporter = remove(args, "metricsExporter");
    if (exporter != null) {
      String normalized = exporter.trim().toLowerCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        if (!normalized.equals("otlp") && !normalized.equals("none")) {
          throw new IllegalArgumentException("metricsExporter must be 'otlp' or 'none'");
        }
        log.debug("Configuring OpenTelemetry metrics exporter: {}", normalized);
        System.setProperty("otel.metrics.exporter", normalized);
      }
    }

    String endpoint = remove(args, "otelEndpoint");
    if (endpoint != null) {
      String trimmed = endpoint.trim();
      if (!trimmed.isEmpty()) {
        validateEndpoint(trimmed);
        log.debug("Configuring OTLP endpoint: {}", trimmed);
        System.setProperty("otel.exporter.otlp.endpoint", trimmed);
      }
    }

    String resourceAttributes = remove(args, "otelResourceAttributes");
    if (resourceAttributes != null) {
      String trimmed = resourceAttributes.trim();
      if (!trimmed.isEmpty()) {
        Strings.requirePrintableAscii("otelResourceAttributes", trimmed, MAX_RESOURCE_ATTRIBUTES_LENGTH);
        log.debug("Configuring OTEL_RESOURCE_ATTRIBUTES override");
        System.setProperty("otel.resource.attributes", trimmed);
      }
    }
  }

  private static void validateEndpoint(String raw) {
    try {
      URI uri = new URI(raw);
      String scheme = uri.getScheme();
      if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
        throw new IllegalArgumentException("otelEndpoint must use http or https scheme");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new IllegalArgumentException("otelEndpoint must include a host");
      }
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("otelEndpoint must be a valid URI", ex);
    }
  }

  private static String remove(Map<String, String> map, String key) {
    if (!map.containsKey(key)) {
      return null;
    }
    return map.remove(key);
  }
}
