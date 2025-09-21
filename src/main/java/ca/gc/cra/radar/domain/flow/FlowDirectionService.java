package ca.gc.cra.radar.domain.flow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks flow orientation (client/server) using SYN observation and fallback heuristics.
 * <p>Thread-safe; maintains state in a concurrent map keyed by unordered endpoint pairs.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class FlowDirectionService {
  /**
   * Creates an empty flow direction service.
   *
   * @since RADAR 0.1-doc
   */
  public FlowDirectionService() {}

  private final Map<String, End> clients = new ConcurrentHashMap<>();

  /**
   * Determines whether the current segment direction is from the client side.
   *
   * @param src source IP address
   * @param sport source TCP port
   * @param dst destination IP address
   * @param dport destination TCP port
   * @param syn {@code true} when the segment carries SYN
   * @param ack {@code true} when the segment carries ACK
   * @return {@code true} if classified as client-to-server
   * @implNote When no prior orientation exists, the first participant is assumed to be the client.
   * @since RADAR 0.1-doc
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
   * @since RADAR 0.1-doc
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
