/**
 * Per-flow context objects shared across assemble-stage ports.
 * <p><strong>Role:</strong> Domain value types that carry flow identity from capture toward sink.</p>
 * <p><strong>Concurrency:</strong> Records are immutable; safe to share across threads.</p>
 * <p><strong>Performance:</strong> Tiny immutable carriers designed for allocation-friendly per-segment usage.</p>
 * <p><strong>Metrics:</strong> Flow metadata feeds tags such as {@code flow.direction} on assemble metrics.</p>
 * <p><strong>Security:</strong> Contains only network metadata (five-tuple); no sensitive payloads retained.</p>
 */
package ca.gc.cra.radar.application.port.context;
