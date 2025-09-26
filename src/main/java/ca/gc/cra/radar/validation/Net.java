package ca.gc.cra.radar.validation;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * <strong>What:</strong> Network endpoint validation utilities for RADAR CLI and adapter configuration.
 * <p><strong>Why:</strong> Prevents malformed host:port pairs from reaching capture adapters and sink clients,
 * avoiding obscure connection failures at runtime.
 * <p><strong>Role:</strong> Domain support invoked while wiring adapters prior to capture → assemble → sink operations.
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Normalize and validate hostnames, IPv4, and IPv6 literals.</li>
 *   <li>Enforce port ranges compatible with TCP listeners (1-65535).</li>
 *   <li>Produce consistent error messaging for CLI feedback.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Immutable and safe for concurrent calls.</p>
 * <p><strong>Performance:</strong> O(n) string parsing with cached {@link Pattern} instances.</p>
 * <p><strong>Observability:</strong> No direct metrics; violations surface as {@link IllegalArgumentException}s.</p>
 *
 * @implNote IPv6 addresses must be wrapped in {@code [ ]} to disambiguate the trailing port delimiter.
 * @since 0.1.0
 * @see Numbers
 * @see Strings
 */
public final class Net {
  private static final Pattern HOST_PATTERN = Pattern.compile(
      "(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");
  private static final Pattern IPV4_PATTERN = Pattern.compile("^(?:[0-9]{1,3})(?:\\.[0-9]{1,3}){3}$");

  private Net() {
    // Utility
  }

  /**
   * Validates a host:port string supporting hostnames, IPv4, and IPv6 literals.
   *
   * @param value candidate host:port string provided via CLI or configuration; must be non-null/non-blank
   * @return normalized host:port pair using bracketed IPv6 literals when applicable
   * @throws IllegalArgumentException if the host or port component is invalid or out of range
   *
   * <p><strong>Concurrency:</strong> Thread-safe; operates solely on locals.</p>
   * <p><strong>Performance:</strong> Performs a single pass over the input and reuses compiled regex patterns.</p>
   * <p><strong>Observability:</strong> Does not emit metrics; caller should surface exceptions to users.</p>
   */
  public static String validateHostPort(String value) {
    String sanitized = Strings.requireNonBlank("host:port", value);
    String host;
    String portPart;
    if (sanitized.startsWith("[")) {
      int idx = sanitized.indexOf(']');
      if (idx < 0) {
        throw new IllegalArgumentException("host:port must close IPv6 literal with ']'");
      }
      host = sanitized.substring(1, idx);
      if (idx + 2 > sanitized.length() || sanitized.charAt(idx + 1) != ':') {
        throw new IllegalArgumentException("host:port must include :<port> after IPv6 literal");
      }
      portPart = sanitized.substring(idx + 2);
      validateIpv6(host);
      sanitized = '[' + host + ']';
    } else {
      int lastColon = sanitized.lastIndexOf(':');
      if (lastColon <= 0 || lastColon == sanitized.length() - 1) {
        throw new IllegalArgumentException("host:port must use HOST:PORT format");
      }
      host = sanitized.substring(0, lastColon);
      portPart = sanitized.substring(lastColon + 1);
      if (host.contains(":")) {
        throw new IllegalArgumentException("IPv6 host must be wrapped in [ ]");
      }
      validateHost(host);
      sanitized = host;
    }

    int port;
    try {
      port = Integer.parseInt(portPart);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("port must be numeric (was " + portPart + ")", ex);
    }
    Numbers.requireRange("port", port, 1, 65535);
    return sanitized + ':' + port;
  }

  private static void validateHost(String host) {
    if (IPV4_PATTERN.matcher(host).matches()) {
      String[] parts = host.split("\\.");
      for (String part : parts) {
        int octet = Integer.parseInt(part);
        Numbers.requireRange("IPv4 octet", octet, 0, 255);
      }
      return;
    }
    if (!HOST_PATTERN.matcher(host).matches()) {
      throw new IllegalArgumentException("invalid hostname: " + host);
    }
  }

  private static void validateIpv6(String host) {
    try {
      InetAddress address = InetAddress.getByName(host);
      if (!(address instanceof Inet6Address)) {
        throw new IllegalArgumentException("invalid IPv6 literal: " + host);
      }
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("invalid IPv6 literal: " + host, ex);
    }
  }
}
