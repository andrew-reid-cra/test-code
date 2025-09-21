package ca.gc.cra.radar.infrastructure.capture.libpcap.cstruct;

import jnr.ffi.Runtime;
import jnr.ffi.Struct;

/**
 * JNR representation of POSIX {@code struct timeval}.
 *
 * @since RADAR 0.1-doc
 */
public final class TimeVal extends Struct {
  /** Seconds component of the timestamp. */
  public final SignedLong tv_sec = new SignedLong();
  /** Microseconds component of the timestamp. */
  public final SignedLong tv_usec = new SignedLong();

  /**
   * Creates the struct bound to the supplied runtime.
   *
   * @param runtime JNR runtime used to allocate native memory
   * @since RADAR 0.1-doc
   */
  public TimeVal(Runtime runtime) {
    super(runtime);
  }
}
