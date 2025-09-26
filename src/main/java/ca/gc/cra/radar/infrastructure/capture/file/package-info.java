/**
 * Offline capture adapters that replay on-disk traces for reproducible pipelines.
 * <p>Readers drive {@link ca.gc.cra.radar.application.port.PacketSource} from files, respecting
 * backpressure by blocking on polling loops and emitting OpenTelemetry metrics for read latency,
 * packet throughput, and file exhaustion rates. Downstream queues observe the same flow control as
 * live capture because the adapters surface the identical {@code RawFrame} payloads.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.capture.file;
