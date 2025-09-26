/**
 * TN3270 protocol adapters that reconstruct terminal message flows.
 * <p><strong>Role:</strong> Adapter layer implementing protocol modules, pairing engines, and metrics for TN3270 traffic.</p>
 * <p><strong>Concurrency:</strong> Session state is per-terminal; callers provide flow-level isolation.</p>
 * <p><strong>Performance:</strong> Uses byte-buffer reuse and batching to keep latency low on mainframe sessions.</p>
 * <p><strong>Metrics:</strong> Emits {@code protocol.tn3270.*} counters for screen pairs, retries, and decode failures.</p>
 * <p><strong>Security:</strong> Validates framing, enforces size limits, and redacts credentials before logging.</p>
 */
package ca.gc.cra.radar.infrastructure.protocol.tn3270;
