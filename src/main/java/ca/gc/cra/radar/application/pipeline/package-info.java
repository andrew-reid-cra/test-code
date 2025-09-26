/**
 * Use cases that orchestrate capture → assemble → sink pipelines.
 * <p><strong>Role:</strong> Application layer workflows coordinating ports, metrics, and backpressure.</p>
 * <p><strong>Concurrency:</strong> Pipelines manage dedicated worker pools; create one instance per CLI run.</p>
 * <p><strong>Performance:</strong> Bounded queues and batching keep capture hot paths unblocked.</p>
 * <p><strong>Metrics:</strong> Emits namespaces such as {@code live.persist.*}, {@code assemble.pipeline.*}, and {@code poster.pipeline.*}.</p>
 * <p><strong>Security:</strong> Operates on validated configuration and enforces redaction via logging helpers.</p>
 */
package ca.gc.cra.radar.application.pipeline;
