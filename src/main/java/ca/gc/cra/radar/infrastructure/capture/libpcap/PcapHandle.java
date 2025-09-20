package ca.gc.cra.radar.infrastructure.capture.libpcap;

public interface PcapHandle extends AutoCloseable {
  void setFilter(String bpf) throws PcapException;
  /** Blocks until next packet or timeout; returns false on EOF (offline). */
  boolean next(Pcap.PacketCallback cb) throws PcapException;
  @Override void close();
}
