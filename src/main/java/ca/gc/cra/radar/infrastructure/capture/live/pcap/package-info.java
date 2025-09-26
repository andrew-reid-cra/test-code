/**
 * Live libpcap-backed capture adapters.
 * <p>Adapters wrap JNR bindings to libpcap, enabling BPF filters, immediate mode, and large kernel
 * buffers for sustained 40 Gbps capture. Metrics cover driver poll latency, packet delivery counts,
 * dropped frames, and BPF compilation failures. Starting these adapters requires root or captured
 * interface capabilities and the native libpcap shared library to be available.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture.live.pcap;
