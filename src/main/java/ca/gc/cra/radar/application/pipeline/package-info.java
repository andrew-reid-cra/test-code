/**
 * Application-level pipelines that coordinate capture, assembly, and posting stages.
 * <p>Use cases orchestrate protocol detection, reconstruction, persistence, and metrics wiring.
 * Workflows are stateful and not thread-safe; create a fresh instance per CLI invocation.</p>
 * <p>Each pipeline accepts validated configuration records (see {@code ca.gc.cra.radar.config}) and
 * surfaces operational counters via {@link ca.gc.cra.radar.application.port.MetricsPort}.
 * <p>Live processing offloads persistence to an ExecutorService-backed worker pool so capture and
 * assembly threads never block on sinks; worker names follow the `live-persist-*` convention.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.application.pipeline;
