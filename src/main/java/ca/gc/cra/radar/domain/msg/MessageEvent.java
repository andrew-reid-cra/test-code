package ca.gc.cra.radar.domain.msg;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * Event emitted by a protocol reconstructor representing a logical request or response.
 *
 * @param protocol protocol associated with the event
 * @param type message direction (request/response)
 * @param payload byte stream context from which the message was derived
 * @param metadata additional attributes such as headers or screen coordinates
 * @since RADAR 0.1-doc
 */
public record MessageEvent(
    ProtocolId protocol,
    MessageType type,
    ByteStream payload,
    MessageMetadata metadata) {}
