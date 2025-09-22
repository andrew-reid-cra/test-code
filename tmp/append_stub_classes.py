from pathlib import Path
path = Path(r"src/test/java/ca/gc/cra/radar/capture/pcap/PcapFilePacketSourceTest.java")
text = path.read_text()
if 'private static final class StubPcap' not in text:
    text += """

  private static final class StubPcap implements Pcap {
    private final Path file;

    StubPcap(Path file) {
      this.file = file;
    }

    @Override
    public PcapHandle openLive(String iface, int snapLen, boolean promiscuous,
        int timeoutMs, int bufferBytes, boolean immediate) {
      throw new UnsupportedOperationException("openLive not supported in stub");
    }

    @Override
    public PcapHandle openOffline(Path file, int snapLen) {
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
    private final java.util.List<Frame> frames;
    private final int snaplen;
    private String filter;
    private int index;

    StubHandle(Path file, int snaplen) {
      this.frames = readFrames(file);
      this.snaplen = snaplen;
    }

    @Override
    public void setFilter(String bpf) {
      this.filter = bpf == null ? null : bpf.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean next(Pcap.PacketCallback cb) {
      while (index < frames.size()) {
        Frame frame = frames.get(index++);
        if (!matchesFilter()) {
          continue;
        }
        byte[] data = frame.data();
        int capLen = Math.min(data.length, snaplen);
        byte[] copy = java.util.Arrays.copyOf(data, capLen);
        cb.onPacket(frame.timestampMicros(), copy, capLen);
        return index < frames.size();
      }
      return false;
    }

    private boolean matchesFilter() {
      if (filter == null || filter.isBlank()) {
        return true;
      }
      if (filter.contains("port 80")) {
        return true;
      }
      if (filter.contains("port 443")) {
        return false;
      }
      return true;
    }

    @Override
    public int dataLink() {
      return 1;
    }

    @Override
    public void close() {}

    private static java.util.List<Frame> readFrames(Path file) {
      try {
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (buf.remaining() < 24) {
          return java.util.List.of();
        }
        buf.position(24); // skip global header
        java.util.List<Frame> frames = new java.util.ArrayList<>();
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
          frames.add(new Frame(tsSec * 1_000_000L + tsUsec, data));
          if (orig > incl && buf.remaining() >= (orig - incl)) {
            buf.position(buf.position() + (orig - incl));
          }
        }
        return frames;
      } catch (java.io.IOException ex) {
        throw new IllegalStateException("Failed to read stub pcap", ex);
      }
    }
  }

  private record Frame(long timestampMicros, byte[] data) {}
"""
    path.write_text(text)
