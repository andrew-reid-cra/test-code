package ca.gc.cra.radar.domain.protocol.tn3270;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable identifier for a TN3270 terminal session.
 * <p>Sessions are keyed by the TCP five-tuple and (optionally) the negotiated LU name. Instances
 * precompute canonical string and a stable Murmur3 128-bit session id for efficient reuse across
 * metrics, telemetry, and Kafka partitioning.</p>
 *
 * <p><strong>Thread-safety:</strong> Immutable and therefore safe to share between threads.</p>
 * <p><strong>Performance:</strong> Canonical string and session id are computed once during
 * construction.</p>
 *
 * @since RADAR 0.2.0
 */
public final class SessionKey implements Serializable {
  @Serial
  private static final long serialVersionUID = 1L;

  private final String clientIp;
  private final int clientPort;
  private final String serverIp;
  private final int serverPort;
  private final String luName;
  private final String canonical;
  private final String sessionId;

  /**
   * Creates a session key.
   *
   * @param clientIp client IP address (dotted quad or hostname); must not be {@code null}
   * @param clientPort client TCP port in {@code [0, 65535]}
   * @param serverIp server IP address (dotted quad or hostname); must not be {@code null}
   * @param serverPort server TCP port in {@code [0, 65535]}
   * @param luName optional LU name negotiated during Telnet setup; {@code null} if unavailable
   * @throws NullPointerException if {@code clientIp} or {@code serverIp} is {@code null}
   * @throws IllegalArgumentException if any port is out of range
   */
  public SessionKey(String clientIp, int clientPort, String serverIp, int serverPort, String luName) {
    this.clientIp = Objects.requireNonNull(clientIp, "clientIp");
    this.clientPort = validatePort(clientPort, "clientPort");
    this.serverIp = Objects.requireNonNull(serverIp, "serverIp");
    this.serverPort = validatePort(serverPort, "serverPort");
    this.luName = (luName == null || luName.isBlank()) ? null : luName.trim();
    this.canonical = buildCanonical();
    this.sessionId = Tn3270Hashes.murmur128Hex(canonical);
  }

  private static int validatePort(int port, String label) {
    if (port < 0 || port > 65_535) {
      throw new IllegalArgumentException(label + " must be within [0,65535] (was " + port + ")");
    }
    return port;
  }

  private String buildCanonical() {
    String base = clientIp + ':' + clientPort + '-' + serverIp + ':' + serverPort;
    return luName == null ? base : base + ':' + luName;
  }

  /**
   * Returns the client IP component.
   *
   * @return client IP string; never {@code null}
   */
  public String clientIp() {
    return clientIp;
  }

  /**
   * Returns the client TCP port.
   *
   * @return client port in {@code [0, 65535]}
   */
  public int clientPort() {
    return clientPort;
  }

  /**
   * Returns the server IP component.
   *
   * @return server IP string; never {@code null}
   */
  public String serverIp() {
    return serverIp;
  }

  /**
   * Returns the server TCP port.
   *
   * @return server port in {@code [0, 65535]}
   */
  public int serverPort() {
    return serverPort;
  }

  /**
   * Returns the optional logical unit (LU) name.
   *
   * @return LU name when available; otherwise {@link Optional#empty()}
   */
  public Optional<String> luName() {
    return Optional.ofNullable(luName);
  }

  /**
   * Canonical textual representation ("clientIp:clientPort-serverIp:serverPort[:lu]").
   *
   * @return canonical representation; never {@code null}
   */
  public String canonical() {
    return canonical;
  }

  /**
   * Stable Murmur3 128-bit hex digest derived from {@link #canonical()}.
   *
   * @return lower-case hexadecimal session id suitable for Kafka keys
   */
  public String sessionId() {
    return sessionId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SessionKey other)) {
      return false;
    }
    return clientPort == other.clientPort
        && serverPort == other.serverPort
        && clientIp.equals(other.clientIp)
        && serverIp.equals(other.serverIp)
        && Objects.equals(luName, other.luName);
  }

  @Override
  public int hashCode() {
    int result = clientIp.hashCode();
    result = 31 * result + clientPort;
    result = 31 * result + serverIp.hashCode();
    result = 31 * result + serverPort;
    result = 31 * result + (luName != null ? luName.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return canonical;
  }
}
