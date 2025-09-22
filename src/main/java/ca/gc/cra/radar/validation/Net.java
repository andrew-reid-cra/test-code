package ca.gc.cra.radar.validation;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Network validation helpers for host:port inputs.
 */
public final class Net {
  private static final Pattern HOST_PATTERN = Pattern.compile(
      "(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");
  private static final Pattern IPV4_PATTERN = Pattern.compile("^(?:[0-9]{1,3})(?:\\.[0-9]{1,3}){3}$");

  private Net() {
    // Utility
  }

  /**
   * Validates host:port strings supporting hostnames, IPv4, and IPv6 literals.
   *
   * @param value candidate host:port
   * @return normalized host:port string
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
