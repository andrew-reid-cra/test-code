/**
 * Native struct layouts used by the libpcap JNR bindings.
 * <p>These classes define fixed-size buffers mirroring {@code timeval}, {@code pcap_pkthdr}, and
 * BPF program representations. They are isolated to confine platform-specific handling and keep
 * higher-level adapters independent of JNR internals.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.cstruct;
