package ca.gc.cra.radar.infrastructure.capture.libpcap;

public final class PcapException extends Exception {
  public PcapException(String msg) { super(msg); }
  public PcapException(String msg, Throwable cause) { super(msg, cause); }
}
