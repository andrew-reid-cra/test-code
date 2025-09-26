package ca.gc.cra.radar.domain.msg;

import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * <strong>What:</strong> Event emitted by a protocol reconstructor representing a logical request or response.
 * <p><strong>Why:</strong> Provides assemble pipelines with immutable message metadata for pairing and persistence.</p>
 * <p><strong>Role:</strong> Domain value object flowing from protocol modules to pairing engines.</p>
 * <p><strong>Thread-safety:</strong> Immutable; safe for concurrent sharing.</p>
 * <p><strong>Performance:</strong> Holds references to immutable components; no defensive copying beyond {@link ByteStream} semantics.</p>
 * <p><strong>Observability:</strong> Fields map directly to metrics tags (e.g., {@code protocol}, {@code direction}).</p>
 *
 * @param protocol protocol associated with the event; never {@code null}
 * @param type message direction (request/response); never {@code null}
 * @param payload byte stream context from which the message was derived; may be {@code null} for synthetic events
 * @param metadata additional attributes such as headers or screen coordinates; may be {@code null}
 * @since 0.1.0
 */
public record MessageEvent(
    ProtocolId protocol,
    MessageType type,
    ByteStream payload,
    MessageMetadata metadata) {}
