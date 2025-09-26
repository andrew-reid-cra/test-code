/**
 * Offline capture adapters that replay PCAP traces for reproducible pipelines.
 * <p><strong>Role:</strong> Adapter layer implementing {@link ca.gc.cra.radar.application.port.PacketSource} over files.</p>
 * <p><strong>Concurrency:</strong> Single-threaded readers; compatible with application backpressure.</p>
 * <p><strong>Performance:</strong> Streams bytes from disk and respects rate-limiting when configured.</p>
 * <p><strong>Metrics:</strong> Emits {@code capture.file.*} counters for read latency, packet totals, and EOF detection.</p>
 * <p><strong>Security:</strong> Validates file paths and enforces sandbox directories from configuration.</p>
 */
package ca.gc.cra.radar.infrastructure.capture.file;
