/**
 * Libpcap-backed offline adapters that replay {@code .pcap} and {@code .pcapng} traces.
 * <p><strong>Role:</strong> Adapter layer enabling deterministic testing via file-based packet sources.</p>
 * <p><strong>Concurrency:</strong> Single-threaded readers; native handles encapsulated inside each instance.</p>
 * <p><strong>Performance:</strong> Streams packets sequentially and applies optional rate limiting.</p>
 * <p><strong>Metrics:</strong> Emits {@code capture.file.pcap.*} counters for packets delivered, truncation, and EOF completion.</p>
 * <p><strong>Security:</strong> Validates file paths, honours sandbox constraints, and sanitizes log output.</p>
 */
package ca.gc.cra.radar.infrastructure.capture.file.pcap;
