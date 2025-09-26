/**
 * Kafka adapters that publish assembled segments and poster artifacts.
 * <p><strong>Role:</strong> Adapter layer on the sink side; implements {@link ca.gc.cra.radar.application.port.SegmentPersistencePort}
 * and poster ports using Kafka topics.</p>
 * <p><strong>Concurrency:</strong> Adapters manage their own producer pools; callers interact from capture and poster threads.</p>
 * <p><strong>Performance:</strong> Batches records and reuses serializers to sustain high-throughput posting.</p>
 * <p><strong>Metrics:</strong> Emits {@code sink.kafka.*} counters and timers (latency, retries).</p>
 * <p><strong>Security:</strong> Assumes Kafka credentials provided via configuration; no secrets logged.</p>
 */
package ca.gc.cra.radar.adapter.kafka;
