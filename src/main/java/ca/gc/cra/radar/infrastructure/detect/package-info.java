/**
 * Protocol detection infrastructure bridging raw flow metadata to protocol modules.
 * <p><strong>Role:</strong> Adapter-side helpers implementing {@link ca.gc.cra.radar.application.port.ProtocolDetector}.</p>
 * <p><strong>Concurrency:</strong> Detectors cache immutable signatures; safe for multi-threaded use.</p>
 * <p><strong>Performance:</strong> Uses trie-based lookups and heuristics tuned for per-flow classification.</p>
 * <p><strong>Metrics:</strong> Emits {@code detect.protocol.*} counters for coverage and fallbacks.</p>
 * <p><strong>Security:</strong> Works with sanitized flow metadata; no payload retention.</p>
 */
package ca.gc.cra.radar.infrastructure.detect;
