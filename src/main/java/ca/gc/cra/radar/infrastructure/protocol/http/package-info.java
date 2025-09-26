/**
 * HTTP protocol adapters that reconstruct application-layer exchanges.
 * <p><strong>Role:</strong> Adapter layer implementing protocol modules, flow assemblers, and pairing engines for HTTP.</p>
 * <p><strong>Concurrency:</strong> Session state is per-flow; instances are not shared across threads.</p>
 * <p><strong>Performance:</strong> Parses headers incrementally and reuses buffers to keep up with high-throughput streams.</p>
 * <p><strong>Metrics:</strong> Emits {@code protocol.http.*} counters for message counts, parse errors, and pairing latency.</p>
 * <p><strong>Security:</strong> Redacts sensitive headers before logging and respects configured allowlists.</p>
 */
package ca.gc.cra.radar.infrastructure.protocol.http;
