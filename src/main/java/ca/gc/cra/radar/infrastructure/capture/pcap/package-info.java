/**
 * Shared pcap infrastructure reused by live and offline adapters.
 * <p>Provides thin wrappers around libpcap (via JNR) so adapters can request handles without
 * exposing native details. Components centralise error translation, buffer management, and metric
 * hooks for filter compilation and handle lifecycle events.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture.pcap;
