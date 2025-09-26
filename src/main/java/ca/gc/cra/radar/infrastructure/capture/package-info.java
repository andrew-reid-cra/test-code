/**
 * Capture adapters bridging RADAR packet-source ports to live interfaces and offline traces.
 * <p><strong>Role:</strong> Adapter layer feeding raw frames into the pipeline.</p>
 * <p><strong>Concurrency:</strong> Pollers run single-threaded per device; lifecycle managed by application use cases.</p>
 * <p><strong>Performance:</strong> Uses native libraries (libpcap, pcap4j) and buffer pooling to stay in the capture hot path.</p>
 * <p><strong>Metrics:</strong> Emits {@code capture.*} counters for frames, drops, and errors.</p>
 * <p><strong>Security:</strong> Live sniffers require elevated privileges; adapters validate BPF filters and sanitize device names.</p>
 */
package ca.gc.cra.radar.infrastructure.capture;
