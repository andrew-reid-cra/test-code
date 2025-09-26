/**
 * JNR-backed bindings to the libpcap native API.
 * <p><strong>Role:</strong> Adapter infrastructure exposing typed handle wrappers to capture adapters.</p>
 * <p><strong>Concurrency:</strong> Native handles are not thread-safe; adapters synchronize access.</p>
 * <p><strong>Performance:</strong> Minimal allocation wrappers to keep JNI overhead low.</p>
 * <p><strong>Metrics:</strong> Surfaces events for filter compilation and read errors via {@code capture.pcap.*} counters.</p>
 * <p><strong>Security:</strong> Validates arguments before crossing the native boundary to mitigate misuse.</p>
 */
package ca.gc.cra.radar.infrastructure.capture.pcap.libpcap;
