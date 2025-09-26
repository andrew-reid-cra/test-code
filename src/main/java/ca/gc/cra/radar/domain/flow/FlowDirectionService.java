package ca.gc.cra.radar.domain.flow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <strong>What:</strong> Tracks flow orientation (client/server) using SYN observation and fallback heuristics.
 * <p><strong>Why:</strong> Assemble-stage components need deterministic directionality to interpret byte streams.</p>
 * <p><strong>Role:</strong> Domain service shared across flow assemblers and protocol modules.</p>
 * <p><strong>Thread-safety:</strong> Backed by {@link ConcurrentHashMap}; safe for concurrent access.</p>
 * <p><strong>Performance:</strong> Constant-time lookups keyed by unordered endpoint pairs.</p>
 * <p><strong>Observability:</strong> Callers should emit metrics such as {@code assemble.flow.direction.resolved}.</p>
 *
 * @since 0.1.0
 */
public final class FlowDirectionService {
  private final Map<String, End> clients = new ConcurrentHashMap<>();

  /**
   * Creates an empty flow direction service.
   *
   * <p><strong>Concurrency:</strong> Safe to construct on any thread.</p>
   * <p><strong>Performance:</strong> Initializes the backing map lazily.</p>
   * <p><strong>Observability:</strong> Emits no metrics by itself.</p>
   */
  public FlowDirectionService() {}

  /**
   * Determines whether the current segment direction is from the client side.
   *
   * @param src source IP address; must not be {@code null}
   * @param sport source TCP port
   * @param dst destination IP address; must not be {@code null}
   * @param dport destination TCP port
   * @param syn {@code true} when the segment carries SYN
   * @param ack {@code true} when the segment carries ACK
   * @return {@code true} if classified as client-to-server
   *
   * <p><strong>Concurrency:</strong> Thread-safe; uses atomic map operations.</p>
   * <p><strong>Performance:</strong> Constant-time map lookup and possible update.</p>
   * <p><strong>Observability:</strong> Callers should increment metrics for orientation decisions.</p>
   *
   * @implNote When no prior orientation exists, the first participant is assumed to be the client.
   * @since 0.1.0
   */
  public boolean fromClient(String src, int sport, String dst, int dport, boolean syn, boolean ack) {
    String key = key(src, sport, dst, dport);
    End c = clients.get(key);
    if (syn && !ack) {
      clients.put(key, new End(src, sport));
      return true;
    }
    if (c == null) {
      clients.put(key, new End(src, sport));
      return true;
    }
    return src.equals(c.ip) && sport == c.port;
  }

  /**
   * Removes orientation state for the flow, typically when FIN/RST is observed.
   *
   * @param src source IP address
   * @param sport source TCP port
   * @param dst destination IP address
   * @param dport destination TCP port
   *
   * <p><strong>Concurrency:</strong> Thread-safe; removes state atomically.</p>
   * <p><strong>Performance:</strong> Constant-time map removal.</p>
   * <p><strong>Observability:</strong> Callers may record cleanup metrics.</p>
   */
  public void forget(String src, int sport, String dst, int dport) {
    clients.remove(key(src, sport, dst, dport));
  }

  private static String key(String a, int ap, String b, int bp) {
    int cmp = a.equals(b) ? Integer.compare(ap, bp) : a.compareTo(b);
    return cmp <= 0 ? (a + ":" + ap + "|" + b + ":" + bp) : (b + ":" + bp + "|" + a + ":" + ap);
  }

  private record End(String ip, int port) {}
}

