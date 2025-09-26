/**
 * Domain types representing reconstructed protocol messages and their metadata.
 * <p><strong>Role:</strong> Domain layer outputs of the assemble stage flowing into sink adapters.</p>
 * <p><strong>Concurrency:</strong> Records are immutable; safe to share across threads.</p>
 * <p><strong>Performance:</strong> Lightweight value objects designed for per-message allocation.</p>
 * <p><strong>Metrics:</strong> Metadata feeds {@code assemble.message.*} metrics and poster observability.</p>
 * <p><strong>Security:</strong> Contains message payloads; downstream sinks must handle sensitive data responsibly.</p>
 */
package ca.gc.cra.radar.domain.msg;
