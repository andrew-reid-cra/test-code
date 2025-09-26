/**
 * Network adapters that decode frames and assemble TCP flows.
 * <p><strong>Role:</strong> Adapter layer implementing flow assembly ports for the assemble stage.</p>
 * <p><strong>Concurrency:</strong> Components synchronize per-flow; callers should isolate flow-specific instances.</p>
 * <p><strong>Performance:</strong> Optimized for back-to-back packet processing with bounded buffering.</p>
 * <p><strong>Metrics:</strong> Emits {@code assemble.flow.*} gauges for backlog and reorder counts.</p>
 * <p><strong>Security:</strong> Handles raw network payloads; adheres to redaction policies before logging.</p>
 */
package ca.gc.cra.radar.infrastructure.net;
