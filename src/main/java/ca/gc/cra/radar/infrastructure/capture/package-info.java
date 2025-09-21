/**
 * Capture adapters backed by libpcap and supporting safe defaults for RADAR ingestion.
 * <p>Implementations expose BPF gating, dry-run execution, and defensive resource handling to
 * prevent NIC lockups when the CLI exits unexpectedly.</p>
 * <p>All classes are single-threaded; callers control scheduling and should invoke
 * {@link ca.gc.cra.radar.application.port.PacketSource#close()} during shutdown.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture;
