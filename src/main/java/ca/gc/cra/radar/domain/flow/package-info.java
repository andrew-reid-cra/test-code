/**
 * Flow direction services that annotate segments with client/server perspective.
 * <p><strong>Role:</strong> Domain utilities used during assemble to enrich message metadata.</p>
 * <p><strong>Concurrency:</strong> Services use immutable data and are thread-safe.</p>
 * <p><strong>Performance:</strong> Simple lookups executed per segment; keep operations O(1).</p>
 * <p><strong>Metrics:</strong> Outputs feed tags like {@code flow.direction} on assemble counters.</p>
 * <p><strong>Security:</strong> Works on validated five-tuples; no payload inspection.</p>
 */
package ca.gc.cra.radar.domain.flow;
