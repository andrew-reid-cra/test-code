package ca.gc.cra.radar.application.port.context;

import ca.gc.cra.radar.domain.net.FiveTuple;

/**
 * <strong>What:</strong> Immutable context describing the network flow orientation for a segment.
 * <p><strong>Why:</strong> Downstream adapters need to know whether bytes came from the client or server to route pairing logic.</p>
 * <p><strong>Role:</strong> Domain value type passed alongside segments during assemble and poster stages.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Carry the canonical {@link FiveTuple} identifier.</li>
 *   <li>Flag the direction (client -> server or reverse).</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Record is immutable; safe to share between threads.</p>
 * <p><strong>Performance:</strong> Tiny value object designed for per-segment allocation.</p>
 * <p><strong>Observability:</strong> Fields often surface as tags on metrics ({@code flow.direction}).</p>
 *
 * @param flow canonical five-tuple describing the conversation
 * @param fromClient {@code true} when bytes originated from the client side of the flow
 * @since 0.1.0
 */
public record FlowContext(FiveTuple flow, boolean fromClient) {}
