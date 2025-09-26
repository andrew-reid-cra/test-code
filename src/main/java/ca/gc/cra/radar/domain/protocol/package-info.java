/**
 * Protocol identifiers and metadata used to route flows to the correct parser.
 * <p><strong>Role:</strong> Domain classification support bridging detector outputs to reconstructor modules.</p>
 * <p><strong>Concurrency:</strong> Enumerations are immutable and shareable.</p>
 * <p><strong>Performance:</strong> Constant-time lookups used during flow classification.</p>
 * <p><strong>Metrics:</strong> Protocol IDs appear as tags on {@code assemble.protocol.*} metrics.</p>
 * <p><strong>Security:</strong> No sensitive payloads; relies on validated flow metadata.</p>
 */
package ca.gc.cra.radar.domain.protocol;
