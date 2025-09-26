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
 * <strong>What:</strong> {@link PacketSource} adapter that replays frames from an on-disk pcap/pcapng trace.
 * <p><strong>Why:</strong> Lets assemble and poster pipelines operate on captured traffic without requiring live interfaces.</p>
 * <p><strong>Role:</strong> Infrastructure adapter on the capture side.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Open offline traces using libpcap, applying optional BPF filters.</li>
 *   <li>Stream frames as {@link RawFrame} instances in capture order.</li>
 *   <li>Surface exhaustion when the file has been fully consumed.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Not thread-safe; callers must poll from a single thread.</p>
 * <p><strong>Performance:</strong> Performs sequential disk IO; copies only the captured payload for each frame.</p>
 * <p><strong>Observability:</strong> Logs key events (filters, truncation, completion) and should be wrapped with capture metrics.</p>
 *
 * @implNote Supports Ethernet and Linux SLL data link types; others trigger an {@link IllegalArgumentException}.
 * @since 0.1.0
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
   * Creates an offline packet source that replays frames using the default JNR libpcap adapter.
   *
   * @param pcapPath path to an existing pcap/pcapng file; must be readable
   * @param bpf optional BPF expression; blank disables filtering
   * @param snaplen snap length in bytes (0 to disable truncation)
   *
   * <p><strong>Concurrency:</strong> Invoke during single-threaded bootstrap.</p>
   * <p><strong>Performance:</strong> Defers opening the file until {@link #start()}.</p>
   * <p><strong>Observability:</strong> Parameters propagate to startup logs and capture metrics.</p>
   */
  public PcapFilePacketSource(Path pcapPath, String bpf, int snaplen) {
    this(pcapPath, bpf, snaplen, JnrPcapAdapter::new);
  }

  /**
   * Creates an offline packet source with an explicit {@link Pcap} supplier (primarily for tests).
   *
   * @param pcapPath path to the capture file; must not be {@code null}
   * @param bpf optional BPF expression; blank disables filtering
   * @param snaplen snap length in bytes
   * @param pcapSupplier factory that yields a {@link Pcap} binding; must not be {@code null}
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread.</p>
   * <p><strong>Performance:</strong> Defers expensive work until {@link #start()}.</p>
   * <p><strong>Observability:</strong> Allows tests to inject canned adapters for deterministic replay.</p>
   */
  public PcapFilePacketSource(Path pcapPath, String bpf, int snaplen, Supplier<Pcap> pcapSupplier) {
    this.pcapPath = Objects.requireNonNull(pcapPath, "pcapPath").toAbsolutePath().normalize();
    this.filter = (bpf == null || bpf.isBlank()) ? null : bpf.trim();
    this.snaplen = snaplen;
    this.pcapSupplier = Objects.requireNonNull(pcapSupplier, "pcapSupplier");
  }

  /**
   * Opens the capture file and prepares libpcap for replay.
   *
   * @throws Exception if the file cannot be opened or the data link type is unsupported
   *
   * <p><strong>Concurrency:</strong> Call once before polling begins; not thread-safe.</p>
   * <p><strong>Performance:</strong> Performs filesystem checks and libpcap initialization.</p>
   * <p><strong>Observability:</strong> Logs filter configuration and link-type details.</p>
   */
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

  /**
   * Reads the next frame from the capture file.
   *
   * @return {@link Optional#empty()} when EOF has been reached; otherwise a {@link RawFrame}
   * @throws Exception if libpcap encounters an error
   *
   * <p><strong>Concurrency:</strong> Invoke from a single polling thread.</p>
   * <p><strong>Performance:</strong> Copies captured bytes into a fresh array; sequential read from disk.</p>
   * <p><strong>Observability:</strong> Logs when snaplen truncation occurs and updates internal counters.</p>
   */
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

  /**
   * Reports whether the capture file has been fully replayed.
   *
   * @return {@code true} after EOF has been reached
   *
   * <p><strong>Concurrency:</strong> Safe for polling and control threads.</p>
   * <p><strong>Performance:</strong> Constant-time check.</p>
   * <p><strong>Observability:</strong> Exposed for higher-level shutdown logic; no direct metrics.</p>
   */
  @Override
  public boolean isExhausted() {
    return exhausted;
  }

  /**
   * Releases libpcap resources and logs capture summary information.
   *
   * @throws Exception if handle or adapter closure fails
   *
   * <p><strong>Concurrency:</strong> Call after polling stops; not thread-safe.</p>
   * <p><strong>Performance:</strong> Flushes libpcap state; may block briefly.</p>
   * <p><strong>Observability:</strong> Logs packet counts and file name.</p>
   */
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
