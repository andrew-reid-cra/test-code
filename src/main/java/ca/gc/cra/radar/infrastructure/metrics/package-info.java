/**
 * Metrics adapters that bridge RADAR ports to OpenTelemetry or no-op implementations.
 * <p><strong>Role:</strong> Adapter layer on the observability plane.</p>
 * <p><strong>Concurrency:</strong> Implementations are thread-safe and support concurrent metric updates.</p>
 * <p><strong>Performance:</strong> Batches and caches instruments to keep hot-path overhead low.</p>
 * <p><strong>Metrics:</strong> Publishes under {@code capture.*}, {@code assemble.*}, and {@code sink.*} namespaces.</p>
 * <p><strong>Security:</strong> Avoids exporting payload contents; only metadata tags are emitted.</p>
 */
package ca.gc.cra.radar.infrastructure.metrics;
