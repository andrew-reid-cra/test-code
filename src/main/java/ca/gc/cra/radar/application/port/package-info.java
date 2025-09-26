/**
 * <strong>Purpose:</strong> Core domain ports defining the capture -> assemble -> sink workflow contracts.
 * <p><strong>Pipeline role:</strong> Domain layer; adapters implement these interfaces to integrate external systems.</p>
 * <p><strong>Concurrency:</strong> Port implementations must be thread-safe unless documented otherwise.</p>
 * <p><strong>Performance:</strong> Contracts favor streaming APIs to minimize allocations.</p>
 * <p><strong>Observability:</strong> Ports expose hooks for metrics/logging but do not prescribe implementations.</p>
 * <p><strong>Security:</strong> Port boundaries assume validated inputs from configuration modules.</p>
 *
 * @since 0.1.0
 */
package ca.gc.cra.radar.application.port;
