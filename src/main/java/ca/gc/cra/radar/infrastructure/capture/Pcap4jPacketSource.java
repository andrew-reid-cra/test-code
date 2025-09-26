package ca.gc.cra.radar.infrastructure.capture;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import org.pcap4j.core.BpfProgram;
import org.pcap4j.core.NotOpenException;
import org.pcap4j.packet.Packet;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.Pcaps;
import org.pcap4j.core.PcapNetworkInterface.PromiscuousMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PacketSource} backed by pcap4j.
 *
 * <p>Designed for parity with the JNI/libpcap adapter so downstream pipelines observe identical
 * {@link RawFrame} payloads and timestamps. Each poll avoids additional copies because pcap4j
 * already produces defensive byte arrays.</p>
 */
public final class Pcap4jPacketSource implements PacketSource {
  private static final Logger log = LoggerFactory.getLogger(Pcap4jPacketSource.class);
  private static final AttributeKey<String> ATTR_IMPL = AttributeKey.stringKey("impl");
  private static final AttributeKey<String> ATTR_SOURCE = AttributeKey.stringKey("source");
  private static final AttributeKey<Boolean> ATTR_OFFLINE = AttributeKey.booleanKey("offline");
  private static final AttributeKey<String> ATTR_STATUS = AttributeKey.stringKey("status");
  private static final String IMPL_LABEL = "pcap4j";
  private static final String OTEL_SCOPE = "ca.gc.cra.radar.capture.pcap4j";

  private final String iface;
  private final Path pcapPath;
  private final int snaplen;
  private final boolean promiscuous;
  private final int timeoutMillis;
  private final int bufferBytes;
  private final boolean immediate;
  private final String filter;
  private final boolean offline;
  private final Telemetry telemetry;

  private PcapHandle handle;
  private long deliveredCount;
  private boolean exhausted;

  /**
   * Creates a live capture source.
   *
   * @param iface network interface name
   * @param snaplen snap length in bytes
   * @param promiscuous whether to enable promiscuous mode
   * @param timeoutMillis poll timeout in milliseconds
   * @param bufferBytes capture buffer size in bytes
   * @param immediate whether to enable immediate mode
   * @param filter optional BPF filter expression; {@code null} or blank disables filtering
   */
  public Pcap4jPacketSource(
      String iface,
      int snaplen,
      boolean promiscuous,
      int timeoutMillis,
      int bufferBytes,
      boolean immediate,
      String filter) {
    this.iface = Objects.requireNonNull(iface, "iface");
    this.pcapPath = null;
    this.snaplen = snaplen;
    this.promiscuous = promiscuous;
    this.timeoutMillis = timeoutMillis;
    this.bufferBytes = bufferBytes;
    this.immediate = immediate;
    this.filter = normalizeFilter(filter);
    this.offline = false;
    this.telemetry = Telemetry.forSource(iface, false);
  }

  /**
   * Creates an offline capture source that replays packets from disk.
   *
   * @param pcapFile path to the capture file
   * @param filter optional BPF filter expression; {@code null} or blank disables filtering
   * @param snaplen snap length in bytes
   */
  public Pcap4jPacketSource(Path pcapFile, String filter, int snaplen) {
    this.iface = null;
    this.pcapPath = Objects.requireNonNull(pcapFile, "pcapFile").toAbsolutePath().normalize();
    this.snaplen = snaplen;
    this.promiscuous = false;
    this.timeoutMillis = 0;
    this.bufferBytes = 0;
    this.immediate = false;
    this.filter = normalizeFilter(filter);
    this.offline = true;
    this.telemetry = Telemetry.forSource(this.pcapPath.toString(), true);
  }

  @Override
  public void start() throws PcapNativeException, NotOpenException, IOException {
    if (handle != null) {
      return;
    }

    Span startSpan = telemetry.tracer.spanBuilder("capture.start")
        .setAttribute("impl", IMPL_LABEL)
        .setAttribute("offline", offline)
        .setAttribute("snaplen", snaplen)
        .setAttribute("filter.present", filter != null)
        .startSpan();
    try {
      if (offline) {
        if (!Files.exists(pcapPath) || !Files.isRegularFile(pcapPath)) {
          throw new IllegalArgumentException("pcapFile must reference an existing file: " + pcapPath);
        }
        if (!Files.isReadable(pcapPath)) {
          throw new IOException("pcapFile is not readable: " + pcapPath);
        }
        this.handle = Pcaps.openOffline(pcapPath.toString());
        if (filter != null) {
          handle.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE);
        }
        exhausted = false;
        deliveredCount = 0L;
        log.info(
            "pcap4j offline capture opened for {} (snaplen={}, datalink={})",
            pcapPath,
            snaplen,
            handle.getDlt());
      } else {
        PcapNetworkInterface nif = Pcaps.getDevByName(iface);
        if (nif == null) {
          throw new IllegalArgumentException("Interface not found: " + iface);
        }
        int effectiveTimeout = immediate ? Math.min(timeoutMillis, 1) : timeoutMillis;
        if (immediate) {
          if (effectiveTimeout == timeoutMillis) {
            log.info("pcap4j immediate mode requested; relying on platform support");
          } else {
            log.info("pcap4j immediate mode approximated via timeout={}ms", effectiveTimeout);
          }
        }
        if (bufferBytes > 0) {
          log.info(
              "pcap4j live capture requested buffer={} bytes; underlying libpcap decides actual size",
              bufferBytes);
        }
        handle = nif.openLive(
            snaplen,
            promiscuous ? PromiscuousMode.PROMISCUOUS : PromiscuousMode.NONPROMISCUOUS,
            Math.max(0, effectiveTimeout));
        if (filter != null) {
          handle.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE);
        }
        exhausted = false;
        deliveredCount = 0L;
        log.info(
            "pcap4j live capture opened on {} (snaplen={}, promisc={}, timeout={}ms, dlt={})",
            iface,
            snaplen,
            promiscuous,
            Math.max(0, effectiveTimeout),
            handle.getDlt());
      }
    } catch (RuntimeException ex) {
      telemetry.errorCounter.add(1, telemetry.withStatus("start.failure"));
      startSpan.recordException(ex);
      throw ex;
    } finally {
      startSpan.end();
    }
  }

  @Override
  public Optional<RawFrame> poll() throws NotOpenException {
    if (handle == null) {
      return Optional.empty();
    }
    long begin = System.nanoTime();
    Span pollSpan = telemetry.tracer.spanBuilder("capture.poll")
        .setAttribute("impl", IMPL_LABEL)
        .setAttribute("offline", offline)
        .startSpan();
    try {
      Packet packet = handle.getNextPacket();
      telemetry.pollLatency.record(System.nanoTime() - begin, telemetry.baseAttributes);
      if (packet == null) {
        if (offline) {
          exhausted = true;
          close();
        }
        return Optional.empty();
      }
      byte[] data = packet.getRawData();
      if (data == null) {
        telemetry.errorCounter.add(1, telemetry.withStatus("null.data"));
        return Optional.empty();
      }
      Timestamp ts = handle.getTimestamp();
      long micros = timestampMicros(ts);
      deliveredCount++;
      telemetry.packetCounter.add(1, telemetry.baseAttributes);
      telemetry.byteCounter.add(data.length, telemetry.baseAttributes);
      pollSpan.setAttribute("bytes", data.length);
      pollSpan.setAttribute("timestamp.micros", micros);
      return Optional.of(new RawFrame(data, micros));
    } catch (NotOpenException ex) {
      telemetry.errorCounter.add(1, telemetry.withStatus("poll.error"));
      pollSpan.recordException(ex);
      throw ex;
    } catch (RuntimeException ex) {
      telemetry.errorCounter.add(1, telemetry.withStatus("poll.failure"));
      pollSpan.recordException(ex);
      throw ex;
    } finally {
      pollSpan.end();
    }
  }

  @Override
  public boolean isExhausted() {
    return exhausted;
  }

  @Override
  public void close() {
    if (handle == null) {
      return;
    }
    try {
      handle.breakLoop();
    } catch (NotOpenException ignored) {
      // already closed
    }
    handle.close();
    handle = null;
    if (offline) {
      log.info("pcap4j offline capture closed for {} after {} packets", pcapPath, deliveredCount);
    } else {
      log.info("pcap4j live capture closed on {} after {} packets", iface, deliveredCount);
    }
  }

  private static String normalizeFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return raw.trim();
  }

  private static long timestampMicros(Timestamp ts) {
    if (ts == null) {
      return 0L;
    }
    long millis = ts.getTime();
    long micros = millis * 1_000L;
    long nanosRemainder = ts.getNanos() % 1_000_000L;
    return micros + (nanosRemainder / 1_000L);
  }

  private record Telemetry(
      Tracer tracer,
      Attributes baseAttributes,
      LongCounter packetCounter,
      LongCounter byteCounter,
      LongCounter errorCounter,
      LongHistogram pollLatency) {

    Attributes withStatus(String status) {
      AttributesBuilder builder = baseAttributes.toBuilder();
      builder.put(ATTR_STATUS, status);
      return builder.build();
    }

    static Telemetry forSource(String source, boolean offline) {
      Meter meter = GlobalOpenTelemetry.getMeter(OTEL_SCOPE);
      Tracer tracer = GlobalOpenTelemetry.getTracer(OTEL_SCOPE);
      Attributes attributes = Attributes.builder()
          .put(ATTR_IMPL, IMPL_LABEL)
          .put(ATTR_SOURCE, source)
          .put(ATTR_OFFLINE, offline)
          .build();
      LongCounter packets = meter.counterBuilder("radar.capture.packets.total")
          .setUnit("1")
          .setDescription("Packets captured by the pcap4j adapter")
          .build();
      LongCounter bytes = meter.counterBuilder("radar.capture.bytes.total")
          .setUnit("By")
          .setDescription("Bytes captured by the pcap4j adapter")
          .build();
      LongCounter errors = meter.counterBuilder("radar.capture.errors.total")
          .setUnit("1")
          .setDescription("Capture errors observed by the pcap4j adapter")
          .build();
      LongHistogram latency = meter.histogramBuilder("radar.capture.poll.latency")
          .ofLongs()
          .setUnit("ns")
          .setDescription("Poll latency for pcap4j packet source")
          .build();
      return new Telemetry(tracer, attributes, packets, bytes, errors, latency);
    }
  }
}


