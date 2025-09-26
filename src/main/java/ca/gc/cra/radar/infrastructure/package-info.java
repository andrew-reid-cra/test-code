/**
 * Infrastructure adapters that bind RADAR ports to external systems (pcap, Kafka, HTTP, metrics).
 * <p><strong>Role:</strong> Adapter layer implementing capture, assemble, and sink contracts.</p>
 * <p><strong>Concurrency:</strong> Each adapter documents its guarantees; many rely on worker pools and lock-free queues.</p>
 * <p><strong>Performance:</strong> Tuned for sustained throughput with buffer reuse and batching.</p>
 * <p><strong>Metrics:</strong> Emits namespaces such as {@code capture.*}, {@code assemble.*}, {@code sink.*}, and {@code poster.*}.</p>
 * <p><strong>Security:</strong> Adheres to configuration-driven credential management and payload redaction policies.</p>
 */
package ca.gc.cra.radar.infrastructure;
