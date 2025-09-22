package ca.gc.cra.radar.testutil;

import ca.gc.cra.radar.infrastructure.capture.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapHandle;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Utility stubs for tests that exercise libpcap adapters without native bindings. */
public final class PcapFixtures {
  private PcapFixtures() {}

  /** Returns a stub {@link Pcap} that replays frames from the provided pcap file. */
  public static Pcap offlineStub(Path file) {
    return new StubPcap(file);
  }

  private static final class StubPcap implements Pcap {
    private final Path file;

    StubPcap(Path file) {
      this.file = file;
    }

    @Override
    public PcapHandle openLive(
        String iface,
        int snapLen,
        boolean promiscuous,
        int timeoutMs,
        int bufferBytes,
        boolean immediate) {
      throw new UnsupportedOperationException("openLive not supported in stub");
    }

    @Override
    public PcapHandle openOffline(Path ignored, int snapLen) {
      return new StubHandle(file, snapLen);
    }

    @Override
    public String libVersion() {
      return "stub";
    }

    @Override
    public void close() {}
  }

  private static final class StubHandle implements PcapHandle {
    private final List<Frame> frames;
    private final int snaplen;
    private String filter;
    private int index;

    StubHandle(Path file, int snaplen) {
      this.frames = readFrames(file);
      this.snaplen = snaplen;
    }

    @Override
    public void setFilter(String bpf) {
      this.filter = bpf == null ? null : bpf.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean next(Pcap.PacketCallback cb) {
      while (index < frames.size()) {
        Frame frame = frames.get(index++);
        if (!matchesFilter(frame)) {
          continue;
        }
        byte[] data = frame.data();
        int capLen = Math.min(data.length, snaplen);
        byte[] copy = java.util.Arrays.copyOf(data, capLen);
        cb.onPacket(frame.timestampMicros(), copy, capLen);
        return true;
      }
      return false;
    }

    private boolean matchesFilter(Frame frame) {
      if (filter == null || filter.isBlank()) {
        return true;
      }
      boolean anyPortClause = false;
      int srcPort = frame.srcPort();
      int dstPort = frame.dstPort();
      if (filter.contains("port 80")) {
        anyPortClause = true;
        if (matchesPort(srcPort, dstPort, 80)) {
          return true;
        }
      }
      if (filter.contains("port 443")) {
        anyPortClause = true;
        if (matchesPort(srcPort, dstPort, 443)) {
          return true;
        }
      }
      if (filter.contains("port 23")) {
        anyPortClause = true;
        if (matchesPort(srcPort, dstPort, 23)) {
          return true;
        }
      }
      if (filter.contains("port 992")) {
        anyPortClause = true;
        if (matchesPort(srcPort, dstPort, 992)) {
          return true;
        }
      }
      return !anyPortClause;
    }

    private static boolean matchesPort(int srcPort, int dstPort, int target) {
      return srcPort == target || dstPort == target;
    }

    @Override
    public int dataLink() {
      return 1;
    }

    @Override
    public void close() {}

    private static List<Frame> readFrames(Path file) {
      try {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.remaining() < 24) {
          return List.of();
        }
        buf.position(24);
        List<Frame> frames = new ArrayList<>();
        while (buf.remaining() >= 16) {
          long tsSec = buf.getInt() & 0xFFFFFFFFL;
          long tsUsec = buf.getInt() & 0xFFFFFFFFL;
          int incl = buf.getInt();
          int orig = buf.getInt();
          if (incl < 0 || incl > buf.remaining()) {
            break;
          }
          byte[] data = new byte[incl];
          buf.get(data);
          Ports ports = extractPorts(data);
          frames.add(new Frame(tsSec * 1_000_000L + tsUsec, data, ports.srcPort(), ports.dstPort()));
          if (orig > incl && buf.remaining() >= (orig - incl)) {
            buf.position(buf.position() + (orig - incl));
          }
        }
        return frames;
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to read stub pcap", ex);
      }
    }
  }

  private record Frame(long timestampMicros, byte[] data, int srcPort, int dstPort) {}

  private record Ports(int srcPort, int dstPort) {}

  private static Ports extractPorts(byte[] data) {
    if (data.length < 14) {
      return new Ports(-1, -1);
    }
    int etherType = ((data[12] & 0xFF) << 8) | (data[13] & 0xFF);
    int offset = 14;
    if (etherType == 0x8100 || etherType == 0x88A8) {
      if (data.length < offset + 4) {
        return new Ports(-1, -1);
      }
      etherType = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
      offset += 4;
    }
    if (etherType != 0x0800 || data.length < offset + 20) {
      return new Ports(-1, -1);
    }
    int ihl = (data[offset] & 0x0F) * 4;
    if (ihl < 20 || data.length < offset + ihl + 4) {
      return new Ports(-1, -1);
    }
    int protocol = data[offset + 9] & 0xFF;
    if (protocol != 6) {
      return new Ports(-1, -1);
    }
    offset += ihl;
    if (data.length < offset + 4) {
      return new Ports(-1, -1);
    }
    int srcPort = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    int dstPort = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    return new Ports(srcPort, dstPort);
  }
}

