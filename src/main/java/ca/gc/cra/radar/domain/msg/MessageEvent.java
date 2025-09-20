package ca.gc.cra.radar.domain.msg;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/** Event emitted by a protocol reconstructor representing a request or response message. */
public record MessageEvent(
    ProtocolId protocol,
    MessageType type,
    ByteStream payload,
    MessageMetadata metadata) {}


