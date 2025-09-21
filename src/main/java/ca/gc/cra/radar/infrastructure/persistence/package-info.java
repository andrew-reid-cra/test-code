/**
 * Persistence adapters for RADAR segment capture and poster outputs.
 * <p>Includes filesystem readers/writers, Kafka bridges, and format-specific sinks that materialize
 * reconstructed pairs. Adapters are deliberately blocking and expect to be invoked from managed
 * worker threads coordinated by the application pipelines.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.persistence;
