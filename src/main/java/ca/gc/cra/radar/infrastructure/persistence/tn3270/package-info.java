/**
 * TN3270-specific persistence adapters that publish reconstructed segments to downstream systems.
 * <p><strong>Role:</strong> Sink-side adapters implementing TN3270 persistence contracts.</p>
 * <p><strong>Concurrency:</strong> Coordinate worker pools to handle high-volume terminal traffic.</p>
 * <p><strong>Performance:</strong> Batches records and reuses encoders for low-latency persistence.</p>
 * <p><strong>Metrics:</strong> Emits {@code sink.tn3270.*} counters for writes, retries, and failures.</p>
 * <p><strong>Security:</strong> Redacts credentials and honors classification policies when storing records.</p>
 */
package ca.gc.cra.radar.infrastructure.persistence.tn3270;
