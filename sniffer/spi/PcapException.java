package sniffer.spi;

public final class PcapException extends Exception {
  public PcapException(String msg) { super(msg); }
  public PcapException(String msg, Throwable cause) { super(msg, cause); }
}
