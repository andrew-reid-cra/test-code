/**
 * JNR libpcap-backed live capture adapters.
 * <p><strong>Role:</strong> Adapter layer integrating native libpcap with RADAR packet sources.</p>
 * <p><strong>Concurrency:</strong> Single-threaded capture loops; native handles guarded inside the adapter.</p>
 * <p><strong>Performance:</strong> Configures immediate mode, large ring buffers, and batched polls for 40 Gbps capture.</p>
 * <p><strong>Metrics:</strong> Emits {@code capture.live.pcap.*} counters for poll latency, packet totals, drops, and BPF errors.</p>
 * <p><strong>Security:</strong> Requires elevated privileges and validates BPF expressions to avoid privileged misuse.</p>
 */
package ca.gc.cra.radar.infrastructure.capture.live.pcap;
