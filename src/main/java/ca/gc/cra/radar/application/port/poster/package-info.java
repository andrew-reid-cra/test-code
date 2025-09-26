/**
 * Poster workflow ports describing how RADAR delivers reconstructed traffic to downstream sinks.
 * <p><strong>Role:</strong> Domain ports on the sink side; implemented by adapters that publish reports or segments.</p>
 * <p><strong>Concurrency:</strong> Ports support concurrent pipelines; implementations document their own guarantees.</p>
 * <p><strong>Performance:</strong> Contracts encourage batched delivery and streaming writes.</p>
 * <p><strong>Metrics:</strong> Poster operations produce {@code poster.*} counters and latency histograms.</p>
 * <p><strong>Security:</strong> Downstream adapters must enforce transport authentication and redact sensitive data in logs.</p>
 */
package ca.gc.cra.radar.application.port.poster;
