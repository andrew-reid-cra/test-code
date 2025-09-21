package ca.gc.cra.radar.infrastructure.capture.libpcap.cstruct;

import jnr.ffi.Runtime;
import jnr.ffi.Struct;

/**
 * JNR representation of libpcap&apos;s {@code struct bpf_program}.
 *
 * @since RADAR 0.1-doc
 */
public final class BpfProgramStruct extends Struct {
  /** Length of the compiled instruction array (in {@code struct bpf_insn}). */
  public final Unsigned32 bf_len = new Unsigned32();
  /** Pointer to the compiled BPF instructions. */
  public final Pointer bf_insns = new Pointer();

  /**
   * Creates the struct bound to the supplied runtime.
   *
   * @param runtime JNR runtime used to allocate native memory
   * @since RADAR 0.1-doc
   */
  public BpfProgramStruct(Runtime runtime) {
    super(runtime);
  }
}
