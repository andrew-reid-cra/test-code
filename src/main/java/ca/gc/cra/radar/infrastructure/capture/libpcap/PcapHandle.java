package ca.gc.cra.radar.infrastructure.capture.libpcap;

/**
 * Represents an activated libpcap handle.
 *
 * @since RADAR 0.1-doc
 */
public interface PcapHandle extends AutoCloseable {
  /**
     * Applies a BPF filter string (runs {@code pcap_compile} + {@code pcap_setfilter}).
   *
   * @param bpf tcpdump-style filter expression
   * @throws PcapException if filter compilation or application fails
   * @since RADAR 0.1-doc
   */
  void setFilter(String bpf) throws PcapException;

  /**
   * Blocks until the next packet or timeout and forwards it to the callback.
   *
   * @param cb callback invoked with packet metadata and bytes
   * @return {@code true} if a packet was delivered; {@code false} on EOF (offline capture)
   * @throws PcapException if libpcap returns an error
   * @since RADAR 0.1-doc
   */
  boolean next(Pcap.PacketCallback cb) throws PcapException;

  /**
   * Closes the underlying capture handle.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  void close();
}
