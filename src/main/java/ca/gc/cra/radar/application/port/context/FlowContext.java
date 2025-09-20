package ca.gc.cra.radar.application.port.context;

import ca.gc.cra.radar.domain.net.FiveTuple;

/** Context provided to FlowAssembler implementations for each segment. */
public record FlowContext(FiveTuple flow, boolean fromClient) {}


