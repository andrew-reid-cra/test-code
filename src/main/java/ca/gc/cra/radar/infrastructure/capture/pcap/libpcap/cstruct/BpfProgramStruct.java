package ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.cstruct;

import jnr.ffi.Runtime;
import jnr.ffi.Struct;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * JNR representation of libpcap&apos;s {@code struct bpf_program}.
 *
 * @since RADAR 0.1-doc
 */
public final class BpfProgramStruct extends Struct {
  /** Length of the compiled instruction array (in {@code struct bpf_insn}). */
  @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "JNR maps struct fields reflectively; keep public for native interop.")
  public final Unsigned32 bf_len = new Unsigned32();
  /** Pointer to the compiled BPF instructions. */
  @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "JNR maps struct fields reflectively; keep public for native interop.")
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
