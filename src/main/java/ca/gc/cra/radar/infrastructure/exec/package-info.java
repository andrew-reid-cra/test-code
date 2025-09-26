/**
 * Executor factories for pipeline worker pools.
 * <p><strong>Role:</strong> Infrastructure utilities configuring thread pools for persistence and poster stages.</p>
 * <p><strong>Concurrency:</strong> Provides thread-safe factory methods that return managed executors.</p>
 * <p><strong>Performance:</strong> Tunes queue sizes and thread naming to balance throughput and diagnostics.</p>
 * <p><strong>Metrics:</strong> Threads emit metrics via configured {@link ca.gc.cra.radar.application.port.MetricsPort} observers.</p>
 * <p><strong>Security:</strong> Threads inherit minimal privileges; names avoid leaking sensitive data.</p>
 */
package ca.gc.cra.radar.infrastructure.exec;
