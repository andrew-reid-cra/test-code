/**
 * Shared libpcap infrastructure used by live and offline capture adapters.
 * <p><strong>Role:</strong> Adapter utilities managing native handles, filter compilation, and buffer reuse.</p>
 * <p><strong>Concurrency:</strong> Handle wrappers guard native calls; individual handles are not shared across threads.</p>
 * <p><strong>Performance:</strong> Minimizes JNI transitions and centralizes buffer pools.</p>
 * <p><strong>Metrics:</strong> Emits {@code capture.pcap.*} counters for handle lifecycle, filter compilation, and errors.</p>
 * <p><strong>Security:</strong> Validates interface names and filters before touching privileged resources.</p>
 */
package ca.gc.cra.radar.infrastructure.capture.pcap;
