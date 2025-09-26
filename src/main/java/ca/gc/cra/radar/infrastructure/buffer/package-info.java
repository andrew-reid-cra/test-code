/**
 * Buffer pooling utilities backing capture and sink IO paths.
 * <p><strong>Role:</strong> Infrastructure adapters providing reusable byte buffers to ports.</p>
 * <p><strong>Concurrency:</strong> Pools use thread-safe structures; callers must honor borrowing contracts.</p>
 * <p><strong>Performance:</strong> Avoids allocations on hot paths by recycling direct and heap buffers.</p>
 * <p><strong>Metrics:</strong> Emits {@code buffer.pool.*} gauges for capacity and borrow latency.</p>
 * <p><strong>Security:</strong> Callers must wipe buffers containing sensitive payloads before reuse.</p>
 */
package ca.gc.cra.radar.infrastructure.buffer;
