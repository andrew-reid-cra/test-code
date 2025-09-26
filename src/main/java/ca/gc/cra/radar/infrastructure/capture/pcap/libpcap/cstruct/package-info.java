/**
 * Native struct layouts backing the libpcap JNR bindings (e.g., {@code timeval}, {@code pcap_pkthdr}).
 * <p><strong>Role:</strong> Adapter infrastructure providing direct, zero-copy views into native buffers.</p>
 * <p><strong>Concurrency:</strong> Instances are not thread-safe; adapters must guard reuse.</p>
 * <p><strong>Performance:</strong> Fixed-size buffers and off-heap memory ensure predictable JNI calls.</p>
 * <p><strong>Metrics:</strong> No direct metrics; higher layers instrument handle usage.</p>
 * <p><strong>Security:</strong> Validates bounds before reading from native memory to avoid overflows.</p>
 */
package ca.gc.cra.radar.infrastructure.capture.pcap.libpcap.cstruct;
