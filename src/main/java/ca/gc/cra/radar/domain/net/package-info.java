/**
 * Domain representations of captured network primitives (frames, segments, flows).
 * <p><strong>Role:</strong> Domain layer inputs produced by capture adapters and consumed by assemble ports.</p>
 * <p><strong>Concurrency:</strong> Types are immutable unless documented otherwise; safe across threads.</p>
 * <p><strong>Performance:</strong> Optimized for zero-copy payload access and minimal header parsing overhead.</p>
 * <p><strong>Metrics:</strong> Attributes drive {@code capture.*} and {@code assemble.flow.*} tagging.</p>
 * <p><strong>Security:</strong> Carries raw packets; treat as sensitive data.</p>
 */
package ca.gc.cra.radar.domain.net;
