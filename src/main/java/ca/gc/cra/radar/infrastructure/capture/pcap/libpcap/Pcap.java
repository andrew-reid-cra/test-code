package ca.gc.cra.radar.infrastructure.capture.pcap.libpcap;

/**
 * High-level wrapper around libpcap operations used by RADAR.
 * <p>Implementations bridge to native libraries via {@link JnrLibpcap}.</p>
 *
 * @since RADAR 0.1-doc
 */
public interface Pcap extends AutoCloseable {
  /**
   * Opens a live capture handle for the specified interface.
   *
   * @param iface network interface name
   * @param snapLen snapshot length in bytes
   * @param promiscuous whether to enable promiscuous mode
   * @param timeoutMs read timeout in milliseconds
   * @param bufferBytes capture buffer size in bytes
   * @param immediate {@code true} to enable immediate mode
   * @return activated capture handle
   * @throws PcapException if any libpcap call fails
   * @since RADAR 0.1-doc
   */
  PcapHandle openLive(String iface, int snapLen, boolean promiscuous,
                      int timeoutMs, int bufferBytes, boolean immediate) throws PcapException;

  /**
   * Opens an offline capture handle backed by a pcap/pcapng file.
   *
   * @param file pcap or pcapng file path
   * @param snapLen snapshot length in bytes used to clamp returned payloads
   * @return activated capture handle
   * @throws PcapException if libpcap fails to open or validate the file
   * @since RADAR 0.1-doc
   */
  PcapHandle openOffline(java.nio.file.Path file, int snapLen) throws PcapException;

  /**
   * Returns the libpcap version string.
   *
   * @return libpcap version string
   * @since RADAR 0.1-doc
   */
  String libVersion();

  /**
   * Callback invoked for each captured packet.
   *
   * @since RADAR 0.1-doc
   */
  interface PacketCallback {
    /**
     * Handles a captured packet.
     *
     * @param tsMicros capture timestamp in microseconds since epoch
     * @param data captured frame bytes (length may exceed {@code capLen})
     * @param capLen number of valid bytes in {@code data}
     * @since RADAR 0.1-doc
     */
    void onPacket(long tsMicros, byte[] data, int capLen);
  }
}
