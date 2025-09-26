/**
 * Offline libpcap adapters that replay {@code .pcap} and {@code .pcapng} traces.
 * <p>Implementations validate datalink support, honour optional BPF filters, and log truncation
 * warnings when packets reach the configured snap length. Metrics cover packets delivered, filter
 * compilation failures, read latency, and EOF completion. Native libpcap must be available on the
 * host executing the offline replay.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture.file.pcap;
