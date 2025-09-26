/**
 * Live capture adapters that pull frames directly from privileged network interfaces.
 * <p>Implementations minimise allocations, reuse libpcap buffers where possible, and emit
 * OpenTelemetry metrics capturing poll latency, packet throughput, and drop counts. Callers must
 * ensure appropriate OS-level privileges (raw socket or capture rights) before starting a live
 * adapter.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture.live;
