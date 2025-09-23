package ca.gc.cra.radar.infrastructure.capture.libpcap;

import java.util.Locale;

import ca.gc.cra.radar.infrastructure.capture.libpcap.cstruct.BpfProgramStruct;
import jnr.ffi.Pointer;
import java.util.Locale;

/**
 * JNR-FFI bindings for the subset of libpcap APIs used by RADAR.
 * <p>Methods map directly to the native {@code pcap_*} functions and are not thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
public interface JnrLibpcap {
  /**
   * Shared library instance used by RADAR capture adapters.
   *
   * @since RADAR 0.1-doc
   */
  
    JnrLibpcap INSTANCE = loadInstance();

  private static JnrLibpcap loadInstance() {
    jnr.ffi.LibraryLoader<JnrLibpcap> loader = jnr.ffi.LibraryLoader.create(JnrLibpcap.class);
    String osName = System.getProperty("os.name", "");
    if (osName.toLowerCase(Locale.ROOT).contains("win")) {
      loader.library("wpcap").library("npcap");
    }
    return loader.load("pcap");
  }

  /**
   * Wraps {@code pcap_create} to allocate a capture handle for the given device.
   *
   * @param source network device (e.g., {@code "eth0"})
   * @param errbuf pointer to an error buffer
   * @return pointer to an unactivated {@code pcap_t} handle or {@code null} on failure
   * @since RADAR 0.1-doc
   */
  Pointer pcap_create(String source, Pointer errbuf);

  /**
   * Opens a capture handle for offline replay of a pcap file.
   *
   * @param fname absolute or relative path to the pcap/pcapng file
   * @param errbuf pointer to an error buffer (at least 256 bytes)
   * @return pointer to an activated {@code pcap_t} handle or {@code null} on failure
   * @since RADAR 0.1-doc
   */
  Pointer pcap_open_offline(String fname, Pointer errbuf);

  /**
   * Sets the snapshot length prior to activation.
   *
   * @param p handle returned by {@link #pcap_create(String, Pointer)}
   * @param snaplen maximum bytes per packet to capture
   * @return zero on success; negative on failure
   * @since RADAR 0.1-doc
   */
  int pcap_set_snaplen(Pointer p, int snaplen);

  /**
   * Toggles promiscuous mode.
   *
   * @param p capture handle
   * @param promisc {@code 1} to enable, {@code 0} to disable
   * @return zero on success; negative on failure
   * @since RADAR 0.1-doc
   */
  int pcap_set_promisc(Pointer p, int promisc);

  /**
   * Sets the read timeout in milliseconds.
   *
   * @param p capture handle
   * @param to_ms timeout in milliseconds
   * @return zero on success; negative on failure
   * @since RADAR 0.1-doc
   */
  int pcap_set_timeout(Pointer p, int to_ms);

  /**
   * Sets the capture buffer size in bytes.
   *
   * @param p capture handle
   * @param bytes requested buffer size in bytes
   * @return zero on success; negative on failure
   * @since RADAR 0.1-doc
   */
  int pcap_set_buffer_size(Pointer p, int bytes);

  /**
   * Enables or disables immediate mode on the capture handle.
   *
   * @param p capture handle
   * @param on {@code 1} to enable immediate mode; {@code 0} to disable
   * @return zero on success; negative on failure
   * @since RADAR 0.1-doc
   */
  int pcap_set_immediate_mode(Pointer p, int on);

  /**
   * Activates the capture handle created via {@link #pcap_create(String, Pointer)}.
   *
   * @param p capture handle
   * @return zero when activation succeeds; positive warning or negative error otherwise
   * @since RADAR 0.1-doc
   */
  int pcap_activate(Pointer p);

  /**
   * Compiles a BPF filter expression.
   *
   * @param p capture handle
   * @param prog program structure populated by libpcap
   * @param filter filter expression in tcpdump syntax
   * @param optimize non-zero to ask libpcap to optimize
   * @param netmask IPv4 netmask used for filter optimization
   * @return zero on success; negative on failure
   * @since RADAR 0.1-doc
   */
  int pcap_compile(Pointer p, BpfProgramStruct prog, String filter, int optimize, int netmask);

  /**
   * Applies a compiled BPF program to the capture handle.
   *
   * @param p capture handle
   * @param prog compiled program produced by {@link #pcap_compile(Pointer, BpfProgramStruct, String, int, int)}
   * @return zero on success; negative on failure
   * @since RADAR 0.1-doc
   */
  int pcap_setfilter(Pointer p, BpfProgramStruct prog);

  /**
   * Releases a compiled BPF program.
   *
   * @param prog compiled program to free
   * @since RADAR 0.1-doc
   */
  void pcap_freecode(BpfProgramStruct prog);

  /**
   * Reads the next available packet.
   *
   * @param p capture handle
   * @param hdr_pp pointer-to-pointer receiving the packet header
   * @param data_pp pointer-to-pointer receiving the packet payload
   * @return libpcap status code (1 packet, 0 timeout, -1 error, -2 EOF)
   * @since RADAR 0.1-doc
   */
  int pcap_next_ex(Pointer p, Pointer hdr_pp, Pointer data_pp);

  /**
   * Returns the data link type for the capture handle.
   *
   * @param p capture handle
   * @return DLT_* constant or negative value on error
   * @since RADAR 0.1-doc
   */
  int pcap_datalink(Pointer p);

  /**
   * Retrieves the last error string for the handle.
   *
   * @param p capture handle
   * @return human-readable error message
   * @since RADAR 0.1-doc
   */
  String pcap_geterr(Pointer p);

  /**
   * Closes a capture handle.
   *
   * @param p capture handle to close
   * @since RADAR 0.1-doc
   */
  void pcap_close(Pointer p);

  /**
   * Returns the libpcap version string as reported by {@code pcap_lib_version}.
   *
   * @return libpcap version string as reported by {@code pcap_lib_version}
   * @since RADAR 0.1-doc
   */
  String pcap_lib_version();
}