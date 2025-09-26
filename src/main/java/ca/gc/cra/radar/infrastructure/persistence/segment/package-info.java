/**
 * File-based segment persistence adapters and binary serialization utilities.
 * <p><strong>Role:</strong> Sink-side adapters persisting segments to disk.</p>
 * <p><strong>Concurrency:</strong> Serializes writes through synchronized channels; callers should not share instances across threads unless documented.</p>
 * <p><strong>Performance:</strong> Streams bytes with buffer pooling to minimize disk thrash.</p>
 * <p><strong>Metrics:</strong> Emits {@code sink.file.*} counters and latency gauges.</p>
 * <p><strong>Security:</strong> Applies filesystem validation; callers responsible for encrypting sensitive payloads at rest.</p>
 */
package ca.gc.cra.radar.infrastructure.persistence.segment;
