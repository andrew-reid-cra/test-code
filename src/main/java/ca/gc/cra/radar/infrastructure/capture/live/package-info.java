/**
 * Live capture adapters that pull frames from privileged network interfaces.
 * <p><strong>Role:</strong> Adapter layer implementing {@link ca.gc.cra.radar.application.port.PacketSource} for live traffic.</p>
 * <p><strong>Concurrency:</strong> Single-threaded polling loops; lifecycle controlled by application use cases.</p>
 * <p><strong>Performance:</strong> Reuses libpcap buffers and applies batching to reduce syscall overhead.</p>
 * <p><strong>Metrics:</strong> Emits {@code capture.live.*} counters for frames, drops, and latency.</p>
 * <p><strong>Security:</strong> Requires raw-socket privileges; validates interface names and BPF filters.</p>
 */
package ca.gc.cra.radar.infrastructure.capture.live;
