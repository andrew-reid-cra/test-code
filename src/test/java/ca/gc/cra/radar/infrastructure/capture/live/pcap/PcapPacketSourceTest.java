package ca.gc.cra.radar.infrastructure.capture.live.pcap;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.PcapException;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.PcapHandle;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PcapPacketSourceTest {

  @Test
  void pollBeforeStartReturnsEmpty() throws Exception {
    PcapPacketSource source = new PcapPacketSource(
        "eth0", 65535, true, 1_000, 65_536, true, null, FakePcap::unused);

    assertTrue(source.poll().isEmpty());
  }

  @Test
  void startIsIdempotentAndReusesHandle() throws Exception {
    FakeHandle handle = new FakeHandle();
    FakePcap pcap = new FakePcap(handle);
    AtomicInteger supplierCalls = new AtomicInteger();
    Supplier<Pcap> supplier = () -> {
      supplierCalls.incrementAndGet();
      return pcap;
    };

    PcapPacketSource source =
        new PcapPacketSource("eth0", 128, true, 1_000, 65_536, false, "tcp", supplier);

    source.start();
    source.start();

    assertEquals(1, supplierCalls.get());
    assertEquals(1, pcap.openCount());
    assertEquals("tcp", handle.filter());

    source.close();
  }

  @Test
  void pollCopiesFrameBytesAndHonoursTimestamps() throws Exception {
    FakeHandle handle = new FakeHandle();
    FakePcap pcap = new FakePcap(handle);
    handle.enqueueFrame(10L, new byte[] {1, 2, 3}, 3);

    PcapPacketSource source = new PcapPacketSource(
        "eth0", 65535, true, 1_000, 65_536, true, null, () -> pcap);

    source.start();

    Optional<RawFrame> frame = source.poll();
    assertTrue(frame.isPresent());
    RawFrame raw = frame.get();
    assertEquals(10L, raw.timestampMicros());
    assertArrayEquals(new byte[] {1, 2, 3}, raw.data());

    byte[] original = handle.lastDeliveredBuffer();
    assertNotNull(original);
    original[0] = 42;
    assertArrayEquals(new byte[] {1, 2, 3}, raw.data());

    source.close();
  }

  @Test
  void pollReturnsEmptyWhenNoPacketDelivered() throws Exception {
    FakeHandle handle = new FakeHandle();
    FakePcap pcap = new FakePcap(handle);
    handle.enqueueNoPacket();

    PcapPacketSource source = new PcapPacketSource(
        "eth0", 64, true, 1_000, 65_536, true, null, () -> pcap);
    source.start();

    assertTrue(source.poll().isEmpty());

    source.close();
  }

  @Test
  void appliesFilterWhenProvidedAndIgnoresBlankStrings() throws Exception {
    FakeHandle handle1 = new FakeHandle();
    FakePcap pcap1 = new FakePcap(handle1);

    PcapPacketSource filtered = new PcapPacketSource(
        "eth0", 128, true, 1_000, 65_536, false, "port 80", () -> pcap1);
    filtered.start();
    assertEquals("port 80", handle1.filter());
    filtered.close();

    FakeHandle handle2 = new FakeHandle();
    FakePcap pcap2 = new FakePcap(handle2);

    PcapPacketSource blankFilter = new PcapPacketSource(
        "eth0", 128, true, 1_000, 65_536, false, "   ", () -> pcap2);
    blankFilter.start();
    assertNull(handle2.filter());
    assertEquals(0, handle2.setFilterCount());
    blankFilter.close();
  }

  @Test
  void pollClosesResourcesOnEof() throws Exception {
    FakeHandle handle = new FakeHandle();
    FakePcap pcap = new FakePcap(handle);
    handle.enqueueFrame(1L, new byte[] {9}, 1);
    handle.enqueueEof();

    PcapPacketSource source = new PcapPacketSource(
        "eth0", 512, true, 500, 65_536, false, null, () -> pcap);
    source.start();

    assertTrue(source.poll().isPresent());
    assertTrue(source.poll().isEmpty());
    assertTrue(handle.isClosed());
    assertEquals(1, handle.closeCount());
    assertTrue(pcap.isClosed());
    assertEquals(1, pcap.closeCount());
  }

  @Test
  void pollHandlesZeroAndNegativeCapLen() throws Exception {
    FakeHandle handle = new FakeHandle();
    FakePcap pcap = new FakePcap(handle);
    handle.enqueueFrame(1L, new byte[] {1, 2, 3}, 0);
    handle.enqueueFrame(2L, new byte[] {4, 5, 6}, -5);

    PcapPacketSource source = new PcapPacketSource(
        "eth0", 128, true, 1_000, 65_536, true, null, () -> pcap);
    source.start();

    Optional<RawFrame> first = source.poll();
    assertTrue(first.isPresent());
    assertArrayEquals(new byte[0], first.get().data());

    Optional<RawFrame> second = source.poll();
    assertTrue(second.isPresent());
    assertArrayEquals(new byte[0], second.get().data());

    source.close();
  }

  @Test
  void pollPropagatesExceptions() throws Exception {
    FakeHandle handle = new FakeHandle();
    FakePcap pcap = new FakePcap(handle);
    handle.enqueueException(new PcapException("boom"));

    PcapPacketSource source = new PcapPacketSource(
        "eth0", 128, true, 1_000, 65_536, true, null, () -> pcap);
    source.start();

    PcapException ex = assertThrows(PcapException.class, source::poll);
    assertEquals("boom", ex.getMessage());
  }

  @Test
  void closeReleasesHandleAndPcapIdempotently() throws Exception {
    FakeHandle handle = new FakeHandle();
    FakePcap pcap = new FakePcap(handle);
    PcapPacketSource source = new PcapPacketSource(
        "eth0", 128, true, 1_000, 65_536, true, null, () -> pcap);

    source.start();
    source.close();
    source.close();

    assertEquals(1, handle.closeCount());
    assertTrue(handle.isClosed());
    assertEquals(1, pcap.closeCount());
    assertTrue(pcap.isClosed());
  }

  private static final class FakePcap implements Pcap {
    private final FakeHandle handle;
    private int openCount;
    private int closeCount;
    private boolean closed;

    FakePcap(FakeHandle handle) {
      this.handle = handle;
    }

    static Pcap unused() {
      throw new AssertionError("should not request pcap without start");
    }

    @Override
    public PcapHandle openLive(
        String iface,
        int snap,
        boolean promisc,
        int timeoutMs,
        int bufferBytes,
        boolean immediate) {
      openCount++;
      closed = false;
      return handle;
    }

    @Override
    public PcapHandle openOffline(java.nio.file.Path file, int snap) {
      return handle;
    }

    @Override
    public String libVersion() {
      return "fake";
    }


    @Override
    public void close() {
      closed = true;
      closeCount++;
    }

    int openCount() {
      return openCount;
    }

    int closeCount() {
      return closeCount;
    }

    boolean isClosed() {
      return closed;
    }
  }

  private static final class FakeHandle implements PcapHandle {
    private enum StepType { FRAME, NO_PACKET, EOF, THROW }

    private static final class Step {
      final StepType type;
      final Frame frame;
      final PcapException exception;

      Step(StepType type, Frame frame, PcapException exception) {
        this.type = type;
        this.frame = frame;
        this.exception = exception;
      }
    }

    private static final class Frame {
      final long ts;
      final byte[] data;
      final int capLen;

      Frame(long ts, byte[] data, int capLen) {
        this.ts = ts;
        this.data = data;
        this.capLen = capLen;
      }
    }

    private final Queue<Step> steps = new ArrayDeque<>();
    private byte[] lastDeliveredBuffer;
    private String filter;
    private int setFilterCount;
    private boolean closed;
    private int closeCount;

    void enqueueFrame(long tsMicros, byte[] data, int capLen) {
      steps.add(new Step(StepType.FRAME, new Frame(tsMicros, data, capLen), null));
    }

    void enqueueNoPacket() {
      steps.add(new Step(StepType.NO_PACKET, null, null));
    }

    void enqueueEof() {
      steps.add(new Step(StepType.EOF, null, null));
    }

    void enqueueException(PcapException ex) {
      steps.add(new Step(StepType.THROW, null, ex));
    }

    byte[] lastDeliveredBuffer() {
      return lastDeliveredBuffer;
    }

    String filter() {
      return filter;
    }

    int setFilterCount() {
      return setFilterCount;
    }

    boolean isClosed() {
      return closed;
    }

    int closeCount() {
      return closeCount;
    }

    @Override
    public void setFilter(String bpf) {
      filter = bpf;
      setFilterCount++;
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
      return switch (step.type) {
        case FRAME -> {
          Frame frame = step.frame;
          lastDeliveredBuffer = frame.data;
          cb.onPacket(frame.ts, frame.data, frame.capLen);
          yield true;
        }
        case NO_PACKET -> true;
        case EOF -> false;
        case THROW -> throw step.exception;
      };
    }

    @Override
    public int dataLink() {
      return 1;
    }

    @Override
    public void close() {
      closed = true;
      closeCount++;
    }
  }
}
