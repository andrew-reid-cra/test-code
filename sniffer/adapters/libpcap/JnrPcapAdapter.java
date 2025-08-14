package sniffer.adapters.libpcap;

import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import sniffer.adapters.libpcap.cstruct.BpfProgramStruct;
import sniffer.adapters.libpcap.cstruct.PcapPkthdr;
import sniffer.spi.Pcap;
import sniffer.spi.PcapException;
import sniffer.spi.PcapHandle;

import java.util.Arrays;

/**
 * JNR libpcap adapter with zero per-packet heap allocation:
 * uses a per-thread reusable scratch buffer that grows as needed.
 */
public class JnrPcapAdapter implements Pcap {
  private final JnrLibpcap p = JnrLibpcap.INSTANCE;
  private final Runtime rt = Runtime.getSystemRuntime();

  @Override
  public PcapHandle openLive(String iface, int snap, boolean promisc, int timeoutMs,
                             int bufferBytes, boolean immediate) throws PcapException {
    Pointer err = Memory.allocateDirect(rt, 256);
    Pointer ph = p.pcap_create(iface, err);
    if (ph == null) throw new PcapException("pcap_create failed");

    check(ph, p.pcap_set_snaplen(ph, snap), "pcap_set_snaplen");
    check(ph, p.pcap_set_promisc(ph, promisc ? 1 : 0), "pcap_set_promisc");
    check(ph, p.pcap_set_timeout(ph, timeoutMs), "pcap_set_timeout");
    check(ph, p.pcap_set_buffer_size(ph, bufferBytes), "pcap_set_buffer_size");
    check(ph, p.pcap_set_immediate_mode(ph, immediate ? 1 : 0), "pcap_set_immediate_mode");

    int rc = p.pcap_activate(ph);
    if (rc != 0) throw new PcapException("pcap_activate: " + p.pcap_geterr(ph));

    return new HandleImpl(ph, rt, p, snap);
  }

  @Override public String libVersion() { return p.pcap_lib_version(); }

  private void check(Pointer ph, int rc, String where) throws PcapException {
    if (rc != 0) throw new PcapException(where + ": " + p.pcap_geterr(ph));
  }

  /** Concrete handle wrapping a pcap_t*. */
  private static final class HandleImpl implements PcapHandle {
    private static final int COPY_CHUNK = 4096; // fewer native -> Java hops per packet
    private static final int SCRATCH_INIT = 2048; // typical MTU; grows as needed

    private final Pointer ph;
    private final Runtime rt;
    private final JnrLibpcap p;
    private final int snaplen;

    private final PcapPkthdr H;
    private final Pointer hdr_pp;
    private final Pointer data_pp;

    // Reusable per-thread scratch buffer (avoids new byte[] per packet)
    private static final ThreadLocal<byte[]> SCRATCH =
        ThreadLocal.withInitial(() -> new byte[SCRATCH_INIT]);

    HandleImpl(Pointer ph, Runtime rt, JnrLibpcap p, int snaplen) {
      this.ph = ph;
      this.rt = rt;
      this.p = p;
      this.snaplen = snaplen > 0 ? snaplen : 65535;
      this.H = new PcapPkthdr(rt);
      this.hdr_pp  = Memory.allocateDirect(rt, rt.addressSize()); // struct pcap_pkthdr*
      this.data_pp = Memory.allocateDirect(rt, rt.addressSize()); // const u_char*
    }

    @Override
    public void setFilter(String bpf) throws PcapException {
      if (bpf == null || bpf.isEmpty()) return;
      var prog = new BpfProgramStruct(rt);
      int rc = p.pcap_compile(ph, prog, bpf, 1, 0xffffffff);
      if (rc != 0) throw new PcapException("pcap_compile: " + p.pcap_geterr(ph));
      try {
        rc = p.pcap_setfilter(ph, prog);
        if (rc != 0) throw new PcapException("pcap_setfilter: " + p.pcap_geterr(ph));
      } finally {
        p.pcap_freecode(prog);
      }
    }

    @Override
    public boolean next(Pcap.PacketCallback cb) throws PcapException {
      int n = p.pcap_next_ex(ph, hdr_pp, data_pp);
      if (n == 1) {
        Pointer hdrPtr = hdr_pp.getPointer(0);
        Pointer pktPtr = data_pp.getPointer(0);

        if (pktPtr == null || pktPtr.address() == 0) {
          // Defensive: libpcap should not do this, but avoid crashing
          return true;
        }

        // Bind header view and read lengths
        H.useMemory(hdrPtr);
        long tsMicros = H.ts.tv_sec.longValue() * 1_000_000L + H.ts.tv_usec.longValue();

        int caplen = H.caplen.intValue();
        if (caplen < 0) caplen = 0;
        if (caplen > snaplen) caplen = snaplen; // clamp to our configured snaplen

        // Ensure reusable buffer is large enough, grow geometrically up to snaplen
        byte[] buf = SCRATCH.get();
        if (buf.length < caplen) {
          int newLen = buf.length;
          while (newLen < caplen) {
            newLen = Math.min(snaplen, Math.max(newLen << 1, caplen));
          }
          buf = Arrays.copyOf(buf, newLen);
          SCRATCH.set(buf);
        }

        // Copy from native into reusable buffer
        int remaining = caplen;
        int off = 0;
        while (remaining > 0) {
          int chunk = Math.min(remaining, COPY_CHUNK);
          pktPtr.get(off, buf, off, chunk);
          remaining -= chunk;
          off += chunk;
        }

        // Pass buffer + authoritative caplen; downstream must honor caplen
        cb.onPacket(tsMicros, buf, caplen);
        return true;

      } else if (n == 0) {
        // Timeout tick; keep looping
        return true;
      } else if (n == -1) {
        throw new PcapException("pcap_next_ex: " + p.pcap_geterr(ph));
      } else { // -2 EOF for offline
        return false;
      }
    }

    @Override public void close() { p.pcap_close(ph); }
  }
}
