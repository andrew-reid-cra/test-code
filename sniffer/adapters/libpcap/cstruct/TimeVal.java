package sniffer.adapters.libpcap.cstruct;

import jnr.ffi.Runtime;
import jnr.ffi.Struct;

public final class TimeVal extends Struct {
  public final SignedLong tv_sec  = new SignedLong();
  public final SignedLong tv_usec = new SignedLong();
  public TimeVal(Runtime r) { super(r); }
}


