package ca.gc.cra.radar.domain.msg;

/**
 * Correlated request/response pair produced by the pairing engine.
 *
 * @param request initiating message; never {@code null}
 * @param response corresponding response or {@code null} when unavailable
 * @since RADAR 0.1-doc
 */
public record MessagePair(MessageEvent request, MessageEvent response) {}
