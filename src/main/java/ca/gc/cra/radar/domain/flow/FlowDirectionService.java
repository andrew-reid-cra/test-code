package ca.gc.cra.radar.domain.flow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks flow orientation (client/server) based on observed SYN packets and heuristics when SYN is
 * absent.
 */
public final class FlowDirectionService {
  private final Map<String, End> clients = new ConcurrentHashMap<>();

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

  public void forget(String src, int sport, String dst, int dport) {
    clients.remove(key(src, sport, dst, dport));
  }

  private static String key(String a, int ap, String b, int bp) {
    int cmp = a.equals(b) ? Integer.compare(ap, bp) : a.compareTo(b);
    return cmp <= 0 ? (a + ":" + ap + "|" + b + ":" + bp) : (b + ":" + bp + "|" + a + ":" + ap);
  }

  private record End(String ip, int port) {}
}


