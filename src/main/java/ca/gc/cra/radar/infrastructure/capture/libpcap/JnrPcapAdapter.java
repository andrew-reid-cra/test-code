package ca.gc.cra.radar.infrastructure.capture.libpcap;

import ca.gc.cra.radar.infrastructure.capture.libpcap.cstruct.BpfProgramStruct;
import ca.gc.cra.radar.infrastructure.capture.libpcap.cstruct.PcapPkthdr;
import java.util.Arrays;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;

/**
 * JNR libpcap adapter with zero per-packet heap allocation: uses a per-thread reusable scratch
 * buffer that grows as needed.
 */
public final class JnrPcapAdapter implements Pcap {
  private final JnrLibpcap p = JnrLibpcap.INSTANCE;
  private final Runtime rt = Runtime.getSystemRuntime();

  /**
   * Creates a libpcap adapter using the default JNR bindings.
   *
   * @since RADAR 0.1-doc
   */
  public JnrPcapAdapter() {}

  /**
   * Opens a live capture handle using libpcap.
   *
   * @param iface network interface name
   * @param snap snap length in bytes
   * @param promisc whether to enable promiscuous mode
   * @param timeoutMs poll timeout in milliseconds
   * @param bufferBytes capture buffer size in bytes
   * @param immediate whether to enable immediate mode
   * @return activated {@link PcapHandle}
   * @throws PcapException if any libpcap call fails
   * @since RADAR 0.1-doc
   */
  @Override
  public PcapHandle openLive(
      String iface,
      int snap,
      boolean promisc,
      int timeoutMs,
      int bufferBytes,
      boolean immediate) throws PcapException {
    Pointer err = Memory.allocateDirect(rt, 256);
    Pointer ph = p.pcap_create(iface, err);
    if (ph == null) {
      throw new PcapException("pcap_create failed");
    }

    check(ph, p.pcap_set_snaplen(ph, snap), "pcap_set_snaplen");
    check(ph, p.pcap_set_promisc(ph, promisc ? 1 : 0), "pcap_set_promisc");
    check(ph, p.pcap_set_timeout(ph, timeoutMs), "pcap_set_timeout");
    check(ph, p.pcap_set_buffer_size(ph, bufferBytes), "pcap_set_buffer_size");
    check(ph, p.pcap_set_immediate_mode(ph, immediate ? 1 : 0), "pcap_set_immediate_mode");

    int rc = p.pcap_activate(ph);
    if (rc != 0) {
      throw new PcapException("pcap_activate: " + p.pcap_geterr(ph));
    }

    return new HandleImpl(ph, rt, p, snap);
  }

  /**
   * No-op for compatibility; live handles must be closed individually.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() {}

  /**
   * Returns the libpcap version string reported by the native library.
   *
   * @return libpcap version string
   * @since RADAR 0.1-doc
   */
  @Override
  public String libVersion() {
    return p.pcap_lib_version();
  }

  private void check(Pointer ph, int rc, String where) throws PcapException {
    if (rc != 0) {
      throw new PcapException(where + ": " + p.pcap_geterr(ph));
    }
  }

  /** Concrete handle wrapping a pcap_t*. */
  private static final class HandleImpl implements PcapHandle {
    private static final int COPY_CHUNK = 4096;
    private static final int SCRATCH_INIT = 2048;

    private final Pointer ph;
    private final Runtime rt;
    private final JnrLibpcap p;
    private final int snaplen;

    private final PcapPkthdr H;
    private final Pointer hdr_pp;
    private final Pointer data_pp;

    private static final ThreadLocal<byte[]> SCRATCH =
        ThreadLocal.withInitial(() -> new byte[SCRATCH_INIT]);

    HandleImpl(Pointer ph, Runtime rt, JnrLibpcap p, int snaplen) {
      this.ph = ph;
      this.rt = rt;
      this.p = p;
      this.snaplen = snaplen > 0 ? snaplen : 65535;
      this.H = new PcapPkthdr(rt);
      this.hdr_pp = Memory.allocateDirect(rt, rt.addressSize());
      this.data_pp = Memory.allocateDirect(rt, rt.addressSize());
    }

    /**
     * Applies a BPF filter to the active capture handle.
     *
     * @param bpf filter expression; {@code null} or blank removes filtering
     * @throws PcapException if compilation or installation fails
     * @since RADAR 0.1-doc
     */
    @Override
    public void setFilter(String bpf) throws PcapException {
      if (bpf == null || bpf.isEmpty()) {
        return;
      }
      var prog = new BpfProgramStruct(rt);
      int rc = p.pcap_compile(ph, prog, bpf, 1, 0xffffffff);
      if (rc != 0) {
        throw new PcapException("pcap_compile: " + p.pcap_geterr(ph));
      }
      try {
        rc = p.pcap_setfilter(ph, prog);
        if (rc != 0) {
          throw new PcapException("pcap_setfilter: " + p.pcap_geterr(ph));
        }
      } finally {
        p.pcap_freecode(prog);
      }
    }

    /**
     * Polls libpcap for the next packet and routes it to the callback.
     *
     * @param cb callback receiving timestamp (microseconds) and packet bytes
     * @return {@code true} to continue polling; {@code false} when EOF is reached
     * @throws PcapException if libpcap signals an error
     * @since RADAR 0.1-doc
     */
    @Override
    public boolean next(Pcap.PacketCallback cb) throws PcapException {
      int n = p.pcap_next_ex(ph, hdr_pp, data_pp);
      if (n == 1) {
        Pointer hdrPtr = hdr_pp.getPointer(0);
        Pointer pktPtr = data_pp.getPointer(0);

        if (pktPtr == null || pktPtr.address() == 0) {
          return true;
        }

        H.useMemory(hdrPtr);
        long tsMicros = H.ts.tv_sec.longValue() * 1_000_000L + H.ts.tv_usec.longValue();

        int caplen = H.caplen.intValue();
        if (caplen < 0) {
          caplen = 0;
        }
        if (caplen > snaplen) {
          caplen = snaplen;
        }

        byte[] buf = SCRATCH.get();
        if (buf.length < caplen) {
          int newLen = buf.length;
          while (newLen < caplen) {
            newLen = Math.min(snaplen, Math.max(newLen << 1, caplen));
          }
          buf = Arrays.copyOf(buf, newLen);
          SCRATCH.set(buf);
        }

        int remaining = caplen;
        int off = 0;
        while (remaining > 0) {
          int chunk = Math.min(remaining, COPY_CHUNK);
          pktPtr.get(off, buf, off, chunk);
          remaining -= chunk;
          off += chunk;
        }

        cb.onPacket(tsMicros, buf, caplen);
        return true;

      } else if (n == 0) {
        return true;
      } else if (n == -1) {
        throw new PcapException("pcap_next_ex: " + p.pcap_geterr(ph));
      } else {
        return false;
      }
    }

    /**
     * Releases the underlying {@code pcap_t} handle.
     *
     * @since RADAR 0.1-doc
     */
    @Override
    public void close() {
      p.pcap_close(ph);
    }
  }
}

