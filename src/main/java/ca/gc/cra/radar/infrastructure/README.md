# RADAR Infrastructure Adapters

## Purpose
Implements application ports for capture, flow assembly helpers, protocol detection, persistence, telemetry, and supporting utilities. Each adapter resides under ca.gc.cra.radar.infrastructure.<area> and may depend on external libraries (pcap4j, Kafka clients, OpenTelemetry).

## Package Overview
- capture.{live,file,pcap} ? Packet sources and helpers for JNI libpcap, pcap4j, and offline replay.
- 
et ? Flow assemblers (ReorderingFlowAssembler, PayloadFlowAssemblerAdapter) and buffer pooling utilities.
- protocol.{http,tn3270} ? Protocol modules wiring detectors, reconstructors, pairing engines, Kafka posters.
- persistence ? File and Kafka persistence adapters plus shared SegmentIoAdapter utilities.
- metrics ? OpenTelemetryMetricsAdapter, bootstrap helpers, and noop fallback.
- exec, uffer, 	ime ? Executor factories, pooled buffers, and clock abstractions reused across adapters.

## Metrics
Adapters must emit detailed metrics via MetricsPort, including:
- Flow assembler counters (*.nullSegment, *.duplicate, *.buffered).
- Protocol payload histograms (protocol.http.bytes, protocol.tn3270.bytes).
- Persistence and poster signals (live.persist.*, ssemble.pairing.error, 	n3270.events.*).
Update [docs/TELEMETRY_GUIDE.md](../../../../../../docs/TELEMETRY_GUIDE.md) when adding new metrics and cover them with unit tests.

## Testing
- Adapters rely on integration-style tests with fake streams (src/test/java/ca/gc/cra/radar/infrastructure/**).
- Wrap JNI or external dependencies (libpcap, Kafka) with test doubles (KafkaSegmentRecordReaderAdapterTest, ReorderingFlowAssemblerTest).

## Extending
- Honour hexagonal boundaries: adapters depend on domain types and application ports only.
- Guard external resources with try-with-resources and emit structured log entries (include pipeline, protocol, sink).
- Reuse buffer pools (BufferPools, PooledBufferedOutputStream) to minimise GC overhead.
- Validate configuration and surface actionable errors before starting pipelines.
