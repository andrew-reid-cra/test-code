package ca.gc.cra.radar.application.port.context;

import ca.gc.cra.radar.domain.net.FiveTuple;

/**
 * Immutable context describing the flow orientation for a TCP segment.
 * <p>Records the five-tuple and whether the bytes originated from the client side.</p>
 *
 * @param flow flow identifier associated with the segment
 * @param fromClient {@code true} when bytes were sent by the client toward the server
 * @since RADAR 0.1-doc
 */
public record FlowContext(FiveTuple flow, boolean fromClient) {}
