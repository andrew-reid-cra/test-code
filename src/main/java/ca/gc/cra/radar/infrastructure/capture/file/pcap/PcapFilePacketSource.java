package ca.gc.cra.radar.infrastructure.capture.file.pcap;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.JnrPcapAdapter;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.PcapHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PacketSource implementation that replays packets from an on-disk pcap or pcapng trace.
 * <p>Provides the same RawFrame payloads produced by live capture so downstream pipelines can
 * remain unchanged.</p>
 */
public final class PcapFilePacketSource implements PacketSource {
  private static final Logger log = LoggerFactory.getLogger(PcapFilePacketSource.class);
  private static final int DLT_EN10MB = 1;
  private static final int DLT_LINUX_SLL = 113;
  private static final int DLT_LINUX_SLL2 = 276;

  private final Path pcapPath;
  private final String filter;
  private final int snaplen;
  private final Supplier<Pcap> pcapSupplier;

  private Pcap pcap;
  private PcapHandle handle;
  private Path canonicalPath;
  private long delivered;
  private volatile boolean exhausted;
  private boolean truncationWarned;

  /**
   * Creates an offline packet source using the default JNR libpcap adapter.
   *
   * @param pcapPath path to an existing pcap or pcapng file
   * @param bpf optional BPF expression; blank disables filtering
   * @param snaplen snap length in bytes
   */
  public PcapFilePacketSource(Path pcapPath, String bpf, int snaplen) {
    this(pcapPath, bpf, snaplen, JnrPcapAdapter::new);
  }

  /**
   * Creates an offline packet source with an explicit Pcap supplier (primarily for tests).
   */
  public PcapFilePacketSource(Path pcapPath, String bpf, int snaplen, Supplier<Pcap> pcapSupplier) {
    this.pcapPath = Objects.requireNonNull(pcapPath, "pcapPath").toAbsolutePath().normalize();
    this.filter = (bpf == null || bpf.isBlank()) ? null : bpf.trim();
    this.snaplen = snaplen;
    this.pcapSupplier = Objects.requireNonNull(pcapSupplier, "pcapSupplier");
  }

  @Override
  public void start() throws Exception {
    if (handle != null) {
      return;
    }
    if (!Files.exists(pcapPath) || !Files.isRegularFile(pcapPath)) {
      throw new IllegalArgumentException("pcapFile must reference an existing file: " + pcapPath);
    }
    if (!Files.isReadable(pcapPath)) {
      throw new IOException("pcapFile is not readable: " + pcapPath);
    }

    this.canonicalPath = pcapPath.toRealPath();
    this.pcap = Objects.requireNonNull(pcapSupplier.get(), "pcap supplier returned null");
    this.handle = pcap.openOffline(canonicalPath, snaplen);
    this.exhausted = false;
    this.delivered = 0L;
    this.truncationWarned = false;

    int datalink = handle.dataLink();
    if (!isSupportedLinkType(datalink)) {
      Path path = effectivePath();
      try {
        close();
      } catch (Exception ex) {
        log.warn("Failed to close pcap handle after datalink rejection", ex);
      }
      throw new IllegalArgumentException(
          "Unsupported data link type " + datalink + " (" + dataLinkName(datalink) + ") for " + path);
    }

    if (filter != null) {
      handle.setFilter(filter);
      log.info("Applied BPF filter '{}' to offline capture", filter);
    } else {
      log.info("No BPF filter applied to offline capture ({}); replaying all packets", effectivePath());
    }
    log.info(
        "Offline capture opened for {} (snaplen={}, datalink={} {})",
        effectivePath(),
        snaplen,
        datalink,
        dataLinkName(datalink));
  }

  @Override
  public Optional<RawFrame> poll() throws Exception {
    if (handle == null) {
      return Optional.empty();
    }

    final RawFrame[] frameHolder = new RawFrame[1];
    boolean more =
        handle.next(
            (tsMicros, data, caplen) -> {
              int length = Math.max(0, caplen);
              byte[] copy = new byte[length];
              if (length > 0) {
                System.arraycopy(data, 0, copy, 0, length);
              }
              if (!truncationWarned && snaplen > 0 && length >= snaplen) {
                log.warn(
                    "Encountered packet reaching snaplen {} while replaying {}; payload may be truncated",
                    snaplen,
                    effectivePath());
                truncationWarned = true;
              }
              frameHolder[0] = new RawFrame(copy, tsMicros);
              delivered++;
            });

    if (!more) {
      exhausted = true;
      close();
      return Optional.empty();
    }
    return Optional.ofNullable(frameHolder[0]);
  }

  @Override
  public boolean isExhausted() {
    return exhausted;
  }

  @Override
  public void close() throws Exception {
    if (handle != null) {
      try {
        handle.close();
      } finally {
        handle = null;
      }
    }
    if (pcap != null) {
      try {
        pcap.close();
      } finally {
        pcap = null;
      }
    }
    Path path = effectivePath();
    if (delivered > 0) {
      log.info("Offline capture closed for {} after {} packets", path, delivered);
    } else {
      log.info("Offline capture closed for {}", path);
    }
  }

  private Path effectivePath() {
    return canonicalPath != null ? canonicalPath : pcapPath;
  }

  private static boolean isSupportedLinkType(int datalink) {
    return datalink == DLT_EN10MB || datalink == DLT_LINUX_SLL || datalink == DLT_LINUX_SLL2;
  }

  private static String dataLinkName(int datalink) {
    return switch (datalink) {
      case DLT_EN10MB -> "EN10MB";
      case DLT_LINUX_SLL -> "LINUX_SLL";
      case DLT_LINUX_SLL2 -> "LINUX_SLL2";
      default -> "DLT_" + datalink;
    };
  }
}
