/**
 * IO helpers shared by persistence adapters (e.g., blob writers).
 * <p><strong>Role:</strong> Infrastructure utilities supporting sink-side file and object storage adapters.</p>
 * <p><strong>Concurrency:</strong> Classes document their own thread-safety; most rely on external synchronization.</p>
 * <p><strong>Performance:</strong> Focus on streaming writes and buffer reuse.</p>
 * <p><strong>Metrics:</strong> Callers annotate {@code sink.io.*} metrics when using these helpers.</p>
 * <p><strong>Security:</strong> Ensure sensitive payloads are encrypted or redacted before persistence.</p>
 */
package ca.gc.cra.radar.infrastructure.persistence.io;
