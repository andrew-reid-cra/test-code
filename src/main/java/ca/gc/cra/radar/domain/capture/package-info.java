/**
 * Domain primitives describing captured segments ready for persistence.
 * <p><strong>Role:</strong> Domain layer types bridging assemble outputs to persistence ports.</p>
 * <p><strong>Concurrency:</strong> Records are immutable and safe to share.</p>
 * <p><strong>Performance:</strong> Designed for zero-copy handoff of payload buffers.</p>
 * <p><strong>Metrics:</strong> Segment properties feed tags on {@code sink.segment.*} metrics.</p>
 * <p><strong>Security:</strong> Contains raw payloads; downstream adapters must enforce access controls and redaction policies.</p>
 */
package ca.gc.cra.radar.domain.capture;
