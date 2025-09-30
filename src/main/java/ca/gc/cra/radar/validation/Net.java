package ca.gc.cra.radar.validation;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Network endpoint validation utilities for RADAR.
 * See class javadoc in your existing file for full description.
 */
public final class Net {

  // RFC-conservative bounds
  private static final int MAX_HOSTNAME_LENGTH = 253;   // total length
  private static final int MAX_LABEL_LENGTH    = 63;    // per label

  // IPv4 dotted-quad shape (fast pre-check); we still range-check octets.
  private static final Pattern IPV4_PATTERN = Pattern.compile("\\A\\d{1,3}(?:\\.\\d{1,3}){3}\\z");

  private Net() {
    // Utility
  }

  /** Validates a host:port string supporting hostnames, IPv4, and IPv6 literals. */
  public static String validateHostPort(String value) {
    final String sanitized = Strings.requireNonBlank("host:port", value).trim();
    final String host;
    final String portPart;
    final String normalizedHost;

    if (sanitized.startsWith("[")) {
      final int idx = sanitized.indexOf(']');
      if (idx < 0) {
        throw new IllegalArgumentException("host:port must close IPv6 literal with ']'");
      }
      host = sanitized.substring(1, idx);
      if (idx + 2 > sanitized.length() || sanitized.charAt(idx + 1) != ':') {
        throw new IllegalArgumentException("host:port must include :<port> after IPv6 literal");
      }
      portPart = sanitized.substring(idx + 2);
      validateIpv6(host);
      normalizedHost = '[' + host + ']';
    } else {
      final int lastColon = sanitized.lastIndexOf(':');
      if (lastColon <= 0 || lastColon == sanitized.length() - 1) {
        throw new IllegalArgumentException("host:port must use HOST:PORT format");
      }
      host = sanitized.substring(0, lastColon);
      portPart = sanitized.substring(lastColon + 1);
      if (host.indexOf(':') >= 0) {
        throw new IllegalArgumentException("IPv6 host must be wrapped in [ ]");
      }
      validateHost(host); // hostname or IPv4
      normalizedHost = host;
    }

    final int port;
    try {
      port = Integer.parseInt(portPart);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("port must be numeric (was " + portPart + ")", ex);
    }
    Numbers.requireRange("port", port, 1, 65535);
    return normalizedHost + ':' + port;
  }

  /**
   * Orchestrates host validation with low cognitive complexity:
   * - IPv4: regex shape + octet range check
   * - Hostname: deterministic label-by-label checks
   */
  private static void validateHost(String host) {
    if (IPV4_PATTERN.matcher(host).matches()) {
      validateIpv4Octets(host);
      return;
    }
    validateHostname(host);
  }

  /** Deterministic hostname validator (ASCII/Punycode). */
  private static void validateHostname(String host) {
    final int len = host.length();
    if (len == 0 || len > MAX_HOSTNAME_LENGTH) {
      throw new IllegalArgumentException("invalid hostname length: " + len + " (must be 1.." + MAX_HOSTNAME_LENGTH + ')');
    }

    int start = 0;
    while (true) {
      final int dot = host.indexOf('.', start);
      final int end = (dot == -1) ? len : dot;
      validateLabel(host, start, end);
      if (dot == -1) {
        break; // last label done
      }
      start = dot + 1;
      if (start == len) {
        throw new IllegalArgumentException("invalid hostname: empty trailing label");
      }
    }
  }

  /**
   * Validates a single label [start,end):
   * - length 1..63
   * - first/last are alnum
   * - interior chars are alnum or '-'
   */
  private static void validateLabel(String s, int start, int end) {
    final int labelLen = end - start;
    if (labelLen <= 0 || labelLen > MAX_LABEL_LENGTH) {
      throw new IllegalArgumentException("invalid hostname: label length " + labelLen + " (must be 1.." + MAX_LABEL_LENGTH + ")");
    }

    final char first = s.charAt(start);
    final char last  = s.charAt(end - 1);
    if (!isAsciiAlnum(first) || !isAsciiAlnum(last)) {
      throw new IllegalArgumentException("invalid hostname: labels must start/end with alphanumeric");
    }

    // Check interior if any
    for (int i = start + 1; i < end - 1; i++) {
      final char c = s.charAt(i);
      if (!(isAsciiAlnum(c) || c == '-')) {
        throw new IllegalArgumentException("invalid hostname: illegal character '" + c + '\'');
      }
    }
  }

  /** Parses and range-checks IPv4 octets (0..255). */
  private static void validateIpv4Octets(String host) {
    int startIndex = 0;
    for (int i = 0; i < 4; i++) {
      final int endIndex = (i < 3) ? host.indexOf('.', startIndex) : host.length();
      final String part = host.substring(startIndex, endIndex);
      final int octet = Integer.parseInt(part);
      Numbers.requireRange("IPv4 octet", octet, 0, 255);
      startIndex = endIndex + 1; // safe when i == 3 (ignored)
    }
  }

  /** Validates a raw IPv6 literal (without brackets) using JDK parsing. */
  private static void validateIpv6(String host) {
    try {
      final InetAddress address = InetAddress.getByName(host);
      if (!(address instanceof Inet6Address)) {
        throw new IllegalArgumentException("invalid IPv6 literal: " + host);
      }
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("invalid IPv6 literal: " + host, ex);
    }
  }

  /** Fast ASCII alphanumeric check (no locale). */
  private static boolean isAsciiAlnum(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9');
  }
}
