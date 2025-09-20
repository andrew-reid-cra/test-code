package ca.gc.cra.radar.infrastructure.capture.libpcap.cstruct;

import jnr.ffi.Runtime;
import jnr.ffi.Struct;

public final class BpfProgramStruct extends Struct {
  public final Unsigned32 bf_len = new Unsigned32();
  public final Pointer bf_insns = new Pointer();

  public BpfProgramStruct(Runtime runtime) {
    super(runtime);
  }
}
