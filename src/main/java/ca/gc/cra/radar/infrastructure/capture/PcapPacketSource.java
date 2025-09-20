package ca.gc.cra.radar.infrastructure.capture;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.infrastructure.capture.libpcap.JnrPcapAdapter;
import ca.gc.cra.radar.infrastructure.capture.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PacketSource adapter backed by the existing JNR libpcap implementation. This is intentionally thin
 * while the capture pipeline is migrated.
 */
public final class PcapPacketSource implements PacketSource {
  private final String iface;
  private final int snaplen;
  private final boolean promiscuous;
  private final int timeoutMillis;
  private final int bufferBytes;
  private final boolean immediate;
  private final String filter;
  private final Supplier<Pcap> pcapSupplier;

  private Pcap pcap;
  private PcapHandle handle;

  public PcapPacketSource(
      String iface,
      int snaplen,
      boolean promiscuous,
      int timeoutMillis,
      int bufferBytes,
      boolean immediate) {
    this(iface, snaplen, promiscuous, timeoutMillis, bufferBytes, immediate, null);
  }

  public PcapPacketSource(
      String iface,
      int snaplen,
      boolean promiscuous,
      int timeoutMillis,
      int bufferBytes,
      boolean immediate,
      String filter) {
    this(iface, snaplen, promiscuous, timeoutMillis, bufferBytes, immediate, filter, JnrPcapAdapter::new);
  }

  PcapPacketSource(
      String iface,
      int snaplen,
      boolean promiscuous,
      int timeoutMillis,
      int bufferBytes,
      boolean immediate,
      String filter,
      Supplier<Pcap> pcapSupplier) {
    this.iface = Objects.requireNonNull(iface, "iface");
    this.snaplen = snaplen;
    this.promiscuous = promiscuous;
    this.timeoutMillis = timeoutMillis;
    this.bufferBytes = bufferBytes;
    this.immediate = immediate;
    this.filter = (filter == null || filter.isBlank()) ? null : filter;
    this.pcapSupplier = Objects.requireNonNull(pcapSupplier, "pcapSupplier");
  }

  @Override
  public void start() throws Exception {
    if (handle != null) {
      return; // already started
    }
    this.pcap = pcapSupplier.get();
    this.handle =
        pcap.openLive(iface, snaplen, promiscuous, timeoutMillis, bufferBytes, immediate);
    if (filter != null && !filter.isEmpty()) {
      handle.setFilter(filter);
    }
  }

  @Override
  public Optional<RawFrame> poll() throws Exception {
    if (handle == null) return Optional.empty();

    final RawFrame[] captured = new RawFrame[1];
    boolean keepRunning =
        handle.next(
            (tsMicros, data, caplen) -> {
              byte[] copy = new byte[Math.max(0, caplen)];
              if (caplen > 0) {
                System.arraycopy(data, 0, copy, 0, caplen);
              }
              captured[0] = new RawFrame(copy, tsMicros);
            });

    if (!keepRunning) {
      close();
      return Optional.empty();
    }

    return Optional.ofNullable(captured[0]);
  }

  @Override
  public void close() throws Exception {
    if (handle != null) {
      handle.close();
      handle = null;
    }
    if (pcap != null) {
      pcap.close();
      pcap = null;
    }
  }
}

