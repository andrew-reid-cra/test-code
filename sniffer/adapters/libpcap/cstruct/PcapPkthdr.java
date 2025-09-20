package sniffer.adapters.libpcap.cstruct;

import jnr.ffi.Runtime;
import jnr.ffi.Struct;

public final class PcapPkthdr extends Struct {
  public final TimeVal ts;
  public final Unsigned32 caplen = new Unsigned32();
  public final Unsigned32 len    = new Unsigned32();
  public PcapPkthdr(Runtime r) { super(r); ts = inner(new TimeVal(r)); }
}


