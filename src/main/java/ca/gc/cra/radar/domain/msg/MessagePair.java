package ca.gc.cra.radar.domain.msg;

/**
 * <strong>What:</strong> Correlated request/response pair produced by the pairing engine.
 * <p><strong>Why:</strong> Provides persistence adapters with complete exchanges suitable for sinks.</p>
 * <p><strong>Role:</strong> Domain value passed from pairing engines to persistence ports.</p>
 * <p><strong>Thread-safety:</strong> Immutable record; safe for sharing.</p>
 * <p><strong>Performance:</strong> Holds references without copying payloads.</p>
 * <p><strong>Observability:</strong> Fields support metrics such as {@code assemble.pair.latency}.</p>
 *
 * @param request initiating message; never {@code null}
 * @param response corresponding response or {@code null} when unavailable
 * @since 0.1.0
 */
public record MessagePair(MessageEvent request, MessageEvent response) {}
