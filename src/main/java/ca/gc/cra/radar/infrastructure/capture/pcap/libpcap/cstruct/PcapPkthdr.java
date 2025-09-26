package ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.cstruct;

import jnr.ffi.Runtime;
import jnr.ffi.Struct;

/**
 * JNR representation of libpcap&apos;s {@code struct pcap_pkthdr}.
 *
 * @since RADAR 0.1-doc
 */
public final class PcapPkthdr extends Struct {
  /** Timestamp of the captured packet. */
  public final TimeVal ts;
  /** Number of bytes actually captured. */
  public final Unsigned32 caplen = new Unsigned32();
  /** Original packet length on the wire. */
  public final Unsigned32 len = new Unsigned32();

  /**
   * Creates the struct bound to the supplied runtime.
   *
   * @param runtime JNR runtime used to allocate native memory
   * @since RADAR 0.1-doc
   */
  public PcapPkthdr(Runtime runtime) {
    super(runtime);
    this.ts = inner(new TimeVal(runtime));
  }
}
