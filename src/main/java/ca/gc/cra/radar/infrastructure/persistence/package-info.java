/**
 * Persistence adapters that deliver segments and poster payloads to durable storage (files, Kafka, HTTP).
 * <p><strong>Role:</strong> Sink-side adapter implementations for RADAR ports.</p>
 * <p><strong>Concurrency:</strong> Designed for managed worker pools; individual adapters document thread safety.</p>
 * <p><strong>Performance:</strong> Emphasizes streaming writes and batching to sustain throughput.</p>
 * <p><strong>Metrics:</strong> Emits {@code sink.*} namespaces covering latency, retries, and queue depth.</p>
 * <p><strong>Security:</strong> Validates target paths and enforces TLS or credential usage as configured.</p>
 */
package ca.gc.cra.radar.infrastructure.persistence;
