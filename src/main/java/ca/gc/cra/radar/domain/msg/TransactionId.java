package ca.gc.cra.radar.domain.msg;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <strong>What:</strong> Generates ULID-style opaque identifiers for correlating protocol messages.
 * <p><strong>Why:</strong> Provides sortable transaction IDs without leaking payload semantics.</p>
 * <p><strong>Role:</strong> Domain utility referenced by persistence and poster adapters.</p>
 * <p><strong>Thread-safety:</strong> Stateless static methods leveraging {@link ThreadLocalRandom}; safe for concurrent use.</p>
 * <p><strong>Performance:</strong> Allocates a 26-character {@link String}; suitable for hot paths.</p>
 * <p><strong>Observability:</strong> IDs can be logged or attached to metrics to trace message lifecycles.</p>
 *
 * @since 0.1.0
 */
public final class TransactionId {
  private static final char[] ENC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

  private TransactionId() {}

  /**
   * Generates a lexicographically sortable identifier composed of timestamp and random bits.
   *
   * @return 26-character ULID-style identifier
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent invocation; thread-local randomness avoids contention.</p>
   * <p><strong>Performance:</strong> Runs in constant time with minimal allocations.</p>
   * <p><strong>Observability:</strong> Callers should treat the returned ID as opaque but sortable.</p>
   *
   * @implNote Uses {@link ThreadLocalRandom}; not suitable for cryptographic purposes.
   * @since 0.1.0
   */
  public static String newId() {
    return buildId(Instant.now().toEpochMilli(), ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong());
  }

  /**
   * Generates a lexicographically sortable identifier using the provided timestamp.
   *
   * @param epochMillis epoch milliseconds component for the identifier
   * @return 26-character ULID-style identifier
   */
  public static String newId(long epochMillis) {
    return buildId(epochMillis, ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong());
  }

  private static String buildId(long epochMillis, long r1, long r2) {
    char[] out = new char[26];
    enc48(epochMillis, out, 0);
    enc80(r1, r2, out);
    return new String(out);
  }

  private static void enc48(long v, char[] d, int o) {
    for (int i = 9; i >= 0; i--) {
      d[o + i] = ENC[(int) (v & 31)];
      v >>>= 5;
    }
  }

  private static void enc80(long r1, long r2, char[] d) {
    long a = (r1 << 16) | ((r2 >>> 48) & 0xFFFFL);
    long b = r2 & 0x0000FFFFFFFFFFFFL;
    for (int i = 25; i >= 10; i--) {
      int idx = (int) ((i >= 18 ? b : a) & 31);
      d[i] = ENC[idx];
      if (i == 18) {
        continue;
      }
      if (i > 18) {
        b >>>= 5;
      } else {
        a >>>= 5;
      }
    }
  }
}

