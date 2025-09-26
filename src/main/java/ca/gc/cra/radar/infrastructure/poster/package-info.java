/**
 * Poster adapters that deliver reconstructed messages to external sinks (files, HTTP, Kafka).
 * <p><strong>Role:</strong> Adapter layer on the sink side implementing poster ports.</p>
 * <p><strong>Concurrency:</strong> Pipelines coordinate internal executors; callers can invoke concurrently.</p>
 * <p><strong>Performance:</strong> Supports batching and streaming writes to minimize latency.</p>
 * <p><strong>Metrics:</strong> Emits {@code poster.pipeline.*} metrics covering enqueue latency, retries, and deliveries.</p>
 * <p><strong>Security:</strong> Applies redaction policies before persistence; relies on configuration for credentials.</p>
 */
package ca.gc.cra.radar.infrastructure.poster;
