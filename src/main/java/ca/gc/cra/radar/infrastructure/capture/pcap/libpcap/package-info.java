/**
 * JNR-backed bindings to the libpcap native API.
 * <p>Handles safe handle lifecycle, BPF compilation, and callback dispatches used by both live and
 * offline adapters. Classes here should remain minimal and avoid additional allocations to protect
 * hot capture paths.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture.pcap.libpcap;
