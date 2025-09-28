package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Strips Telnet negotiation sequences (IAC commands, sub-negotiations) from TN3270 records.
 * <p>Reuses a thread-local direct buffer to avoid allocations in the hot path.</p>
 *
 * @since RADAR 0.2.0
 */
public final class TelnetNegotiationFilter {
  private static final int IAC = 0xFF;
  private static final int SB = 0xFA;
  private static final int SE = 0xF0;
  private static final int WILL = 0xFB;
  private static final int WONT = 0xFC;
  private static final int DO = 0xFD;
  private static final int DONT = 0xFE;
  private static final int EOR = 0xEF;

  private static final ThreadLocal<BufferHolder> BUFFERS = ThreadLocal.withInitial(BufferHolder::new);

  /**
   * Filters Telnet control sequences from the supplied payload.
   *
   * @param payload raw TN3270 bytes including Telnet negotiation
   * @return buffer containing only 3270 application data, positioned at zero and limited to the filtered size
   */
  public ByteBuffer filter(ByteBuffer payload) {
    Objects.requireNonNull(payload, "payload");
    ByteBuffer source = payload.duplicate();
    source.clear();

    BufferHolder holder = BUFFERS.get();
    ByteBuffer out = holder.acquire(source.remaining());

    boolean pendingIac = false;
    boolean inSubnegotiation = false;

    while (source.hasRemaining()) {
      int b = source.get() & 0xFF;
      if (pendingIac) {
        pendingIac = false;
        switch (b) {
          case IAC -> out.put((byte) IAC);
          case SB -> inSubnegotiation = true;
          case SE -> inSubnegotiation = false;
          case WILL, WONT, DO, DONT -> {
            if (source.hasRemaining()) {
              source.get(); // skip option byte
            }
          }
          case EOR -> {
            // swallow explicit end-of-record markers; reconstructor already split records
          }
          default -> {
            if (!inSubnegotiation) {
              out.put((byte) b);
            }
          }
        }
        continue;
      }

      if (b == IAC) {
        pendingIac = true;
        continue;
      }

      if (!inSubnegotiation) {
        out.put((byte) b);
      }
      // TODO: Handle nested TN3270E sequences if encountered.
    }

    out.flip();
    return out;
  }

  private static final class BufferHolder {
    private ByteBuffer buffer = ByteBuffer.allocateDirect(1024);

    ByteBuffer acquire(int required) {
      if (buffer.capacity() < required) {
        int newCapacity = Math.max(required, buffer.capacity() * 2);
        buffer = ByteBuffer.allocateDirect(newCapacity);
      }
      buffer.clear();
      return buffer;
    }
  }
}
