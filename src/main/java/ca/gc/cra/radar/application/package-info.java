/**
 * Application layer orchestration for RADAR pipelines.
 * <p><strong>Role:</strong> Hosts use cases and ports that coordinate capture → assemble → sink stages.</p>
 * <p><strong>Concurrency:</strong> Use cases manage worker pools explicitly; interfaces document caller responsibilities.</p>
 * <p><strong>Performance:</strong> Pipelines stream traffic with bounded queues and backpressure controls.</p>
 * <p><strong>Metrics:</strong> Emits namespaces including {@code live.persist.*}, {@code assemble.usecase.*}, and {@code poster.pipeline.*}.</p>
 * <p><strong>Security:</strong> Relies on validation utilities to reject unsafe configuration before activating adapters.</p>
 */
package ca.gc.cra.radar.application;
