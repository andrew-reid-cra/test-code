package ca.gc.cra.radar.infrastructure.capture.live.pcap;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.JnrPcapAdapter;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.PcapHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * <strong>What:</strong> Live {@link PacketSource} that captures frames from a network interface via libpcap.
 * <p><strong>Why:</strong> Powers the capture stage for production deployments while the architecture continues to evolve.</p>
 * <p><strong>Role:</strong> Infrastructure adapter on the capture side.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Configure and open a libpcap handle with optional BPF filters.</li>
 *   <li>Stream frames into RADAR as {@link RawFrame} objects.</li>
 *   <li>Expose exhaustion when the interface stops delivering traffic.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Not thread-safe; callers must poll from a single capture thread.</p>
 * <p><strong>Performance:</strong> Delegates to libpcap for zero-copy capture; copies payloads into fresh arrays for pipeline safety.</p>
 * <p><strong>Observability:</strong> Should be wrapped with capture metrics (drops, latency) and logs key configuration.</p>
 *
 * @since 0.1.0
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
  private volatile boolean exhausted;

  /**
   * Creates a packet source without a capture filter.
   *
   * @param iface network interface name; must not be {@code null}
   * @param snaplen snap length in bytes (0 disables truncation)
   * @param promiscuous whether to enable promiscuous mode
   * @param timeoutMillis poll timeout in milliseconds
   * @param bufferBytes capture buffer size in bytes
   * @param immediate whether to enable immediate mode
   *
   * <p><strong>Concurrency:</strong> Invoke during single-threaded bootstrap.</p>
   * <p><strong>Performance:</strong> Defers libpcap initialization to {@link #start()}.</p>
   * <p><strong>Observability:</strong> Parameters map to capture metrics and startup logs.</p>
   */
  public PcapPacketSource(
      String iface,
      int snaplen,
      boolean promiscuous,
      int timeoutMillis,
      int bufferBytes,
      boolean immediate) {
    this(iface, snaplen, promiscuous, timeoutMillis, bufferBytes, immediate, null);
  }

  /**
   * Creates a packet source with an optional BPF filter expression.
   *
   * @param iface network interface name; must not be {@code null}
   * @param snaplen snap length in bytes
   * @param promiscuous whether to enable promiscuous mode
   * @param timeoutMillis poll timeout in milliseconds
   * @param bufferBytes capture buffer size in bytes
   * @param immediate whether to enable immediate mode
   * @param filter optional BPF filter expression; {@code null} or blank disables filtering
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread.</p>
   * <p><strong>Performance:</strong> Applies the filter during {@link #start()}.</p>
   * <p><strong>Observability:</strong> Filter configuration is logged for troubleshooting.</p>
   */
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

  /**
   * Internal constructor that allows tests to inject a custom {@link Pcap} binding.
   *
   * @since RADAR 0.1-doc
   */
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

  /**
   * Opens the libpcap handle for the configured interface.
   *
   * @throws Exception if libpcap initialization fails
   *
   * <p><strong>Concurrency:</strong> Call once before polling; not thread-safe.</p>
   * <p><strong>Performance:</strong> Configures buffers, mode flags, and optional BPF filters.</p>
   * <p><strong>Observability:</strong> Callers should log interface and filter details.</p>
   */
  @Override
  public void start() throws Exception {
    if (handle != null) {
      return; // already started
    }
    this.pcap = pcapSupplier.get();
    this.handle =
        pcap.openLive(iface, snaplen, promiscuous, timeoutMillis, bufferBytes, immediate);
    this.exhausted = false;
    if (filter != null && !filter.isEmpty()) {
      handle.setFilter(filter);
    }
  }

  /**
   * Polls for the next frame.
   *
   * @return captured frame when available; otherwise {@link Optional#empty()} (including EOF)
   * @throws Exception if libpcap reports an error
   *
   * <p><strong>Concurrency:</strong> Invoke from a single capture thread.</p>
   * <p><strong>Performance:</strong> Copies captured bytes into a new array; leverages libpcap for polling.</p>
   * <p><strong>Observability:</strong> Callers should record polling latency and drop metrics.</p>
   */
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
      exhausted = true;
      close();
      return Optional.empty();
    }

    return Optional.ofNullable(captured[0]);
  }

  /**
   * Indicates whether the underlying capture has stopped producing frames.
   *
   * @return {@code true} when the libpcap handle reports EOF
   *
   * <p><strong>Concurrency:</strong> Safe for use by polling and control threads.</p>
   * <p><strong>Performance:</strong> Constant-time check.</p>
   * <p><strong>Observability:</strong> Used by pipelines to trigger shutdown; no direct metrics.</p>
   */
  @Override
  public boolean isExhausted() {
    return exhausted;
  }

  /**
   * Closes the capture handle and associated resources.
   *
   * @throws Exception if closing fails
   *
   * <p><strong>Concurrency:</strong> Invoke after polling has stopped; not thread-safe.</p>
   * <p><strong>Performance:</strong> Closes libpcap handles; may block briefly.</p>
   * <p><strong>Observability:</strong> Callers should log shutdown and emit capture stop metrics.</p>
   */
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


