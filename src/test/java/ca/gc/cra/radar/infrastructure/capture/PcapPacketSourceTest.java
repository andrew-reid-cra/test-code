package ca.gc.cra.radar.infrastructure.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.net.RawFrame;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import ca.gc.cra.radar.infrastructure.capture.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapException;
import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapHandle;

class PcapPacketSourceTest {
  @Test
  void pollCopiesFrameBytesAndHonoursTimestamps() throws Exception {
    FakeHandle handle = new FakeHandle();
    handle.enqueueFrame(10L, new byte[] {1, 2, 3});

    Supplier<Pcap> supplier = () -> new FakePcap(handle);
    PcapPacketSource source =
        new PcapPacketSource("eth0", 65535, true, 1_000, 65_536, true, null, supplier);

    source.start();

    Optional<RawFrame> frame = source.poll();
    assertTrue(frame.isPresent());
    RawFrame raw = frame.get();
    assertEquals(10L, raw.timestampMicros());
    assertArrayEquals(new byte[] {1, 2, 3}, raw.data());

    // mutate original array to ensure copy occurred
    Arrays.fill(handle.lastReturned(), (byte) 9);
    assertArrayEquals(new byte[] {1, 2, 3}, raw.data());

    source.close();
  }

  @Test
  void pollReturnsEmptyOnTimeout() throws Exception {
    FakeHandle handle = new FakeHandle();
    handle.enqueueTimeout();

    PcapPacketSource source =
        new PcapPacketSource(
            "eth0", 65535, true, 1_000, 65_536, true, null, () -> new FakePcap(handle));
    source.start();

    assertTrue(source.poll().isEmpty());

    source.close();
  }

  @Test
  void appliesFilterWhenProvided() throws Exception {
    FakeHandle handle = new FakeHandle();
    PcapPacketSource source =
        new PcapPacketSource("eth0", 65535, true, 1_000, 65_536, true, "tcp", () -> new FakePcap(handle));

    source.start();
    assertEquals("tcp", handle.filter());
    source.close();
  }

  private static final class FakePcap implements Pcap {
    private final FakeHandle handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    FakePcap(FakeHandle handle) {
      this.handle = handle;
    }

    @Override
    public PcapHandle openLive(
        String iface,
        int snap,
        boolean promisc,
        int timeoutMs,
        int bufferBytes,
        boolean immediate)
        throws PcapException {
      return handle;
    }

    @Override
    public String libVersion() {
      return "fake";
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }

  private static final class FakeHandle implements PcapHandle {
    private static final class Step {
      final boolean timeout;
      final Frame frame;

      Step(boolean timeout, Frame frame) {
        this.timeout = timeout;
        this.frame = frame;
      }
    }

    private static final class Frame {
      final long ts;
      final byte[] data;

      Frame(long ts, byte[] data) {
        this.ts = ts;
        this.data = data;
      }
    }

    private final Queue<Step> steps = new ArrayDeque<>();
    private byte[] lastReturned;
    private String filter;
    private boolean closed;

    void enqueueFrame(long tsMicros, byte[] data) {
      steps.add(new Step(false, new Frame(tsMicros, data)));
    }

    void enqueueTimeout() {
      steps.add(new Step(true, null));
    }

    byte[] lastReturned() {
      return lastReturned;
    }

    String filter() {
      return filter;
    }

    @Override
    public void setFilter(String bpf) throws PcapException {
      this.filter = bpf;
    }

    @Override
    public boolean next(Pcap.PacketCallback cb) throws PcapException {
      if (closed) {
        throw new PcapException("handle closed");
      }
      Step step = steps.poll();
      if (step == null) {
        return false;
      }
      if (step.timeout) {
        return true;
      }
      Frame frame = Objects.requireNonNull(step.frame);
      lastReturned = frame.data;
      cb.onPacket(frame.ts, frame.data, frame.data.length);
      return true;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}

