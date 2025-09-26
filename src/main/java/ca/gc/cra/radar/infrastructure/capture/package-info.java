/**
 * Capture adapters bridging RADAR's packet-source ports to infrastructure implementations.
 * <p>Subpackages organise adapters by execution mode: {@code live} sniffers drive privileged
 * interface capture while {@code file} readers replay offline traces for deterministic testing
 * and investigations. Shared transport-specific infrastructure (for example libpcap bindings)
 * resides under {@code pcap}.</p>
 * <p>All adapters honour {@link ca.gc.cra.radar.application.port.PacketSource} contracts,
 * propagate OpenTelemetry metrics, and remain single-threaded; callers own lifecycle management.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture;
