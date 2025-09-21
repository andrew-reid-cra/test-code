/**
 * Infrastructure adapters for RADAR: capture sources, protocol processors, persistence,
 * metrics, and time providers.
 * <p>Implements application ports using external systems such as libpcap, Kafka, and file IO.</p>
 * <p>Depends on {@code ca.gc.cra.radar.application} port contracts and {@code ca.gc.cra.radar.domain}
 * value objects.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure;
