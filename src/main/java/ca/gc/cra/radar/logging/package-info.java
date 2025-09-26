/**
 * <strong>Purpose:</strong> Logging utilities that tune verbosity and sanitize payloads before emission.
 * <p><strong>Pipeline role:</strong> Cross-cutting domain support for capture, assemble, and sink diagnostics.
 * <p><strong>Concurrency:</strong> Stateless helpers; thread-safe when invoked from concurrent pipelines.
 * <p><strong>Performance:</strong> Lightweight string operations and SLF4J level toggling.
 * <p><strong>Observability:</strong> Coordinates with SLF4J/Logback; no custom metrics.
 * <p><strong>Security:</strong> Provides redaction helpers to avoid leaking sensitive frame contents.
 *
 * @since 0.1.0
 */
package ca.gc.cra.radar.logging;
