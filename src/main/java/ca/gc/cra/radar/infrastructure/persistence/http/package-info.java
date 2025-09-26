/**
 * HTTP-based persistence adapters that post segments to external services.
 * <p><strong>Role:</strong> Sink-side adapters implementing {@link ca.gc.cra.radar.application.port.SegmentPersistencePort}.</p>
 * <p><strong>Concurrency:</strong> Use async HTTP clients; thread-safe for pipeline use.</p>
 * <p><strong>Performance:</strong> Supports batching and streaming uploads; tuned for high throughput.</p>
 * <p><strong>Metrics:</strong> Emits {@code sink.http.*} counters for status codes, retries, and latency histograms.</p>
 * <p><strong>Security:</strong> Enforces TLS and redacts sensitive payloads in logs; credentials supplied via config.</p>
 */
package ca.gc.cra.radar.infrastructure.persistence.http;
