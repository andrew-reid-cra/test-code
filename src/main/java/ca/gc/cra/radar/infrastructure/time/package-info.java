/**
 * Time-related infrastructure adapters implementing clock ports.
 * <p><strong>Role:</strong> Adapter layer providing concrete time sources.</p>
 * <p><strong>Concurrency:</strong> Implementations are thread-safe.</p>
 * <p><strong>Performance:</strong> Millisecond resolution system calls; minimal overhead.</p>
 * <p><strong>Metrics:</strong> No direct metrics; downstream components tag events with supplied timestamps.</p>
 * <p><strong>Security:</strong> No sensitive data handled.</p>
 */
package ca.gc.cra.radar.infrastructure.time;
