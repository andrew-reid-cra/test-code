# RADAR Infrastructure Adapters

## Purpose
Implements application ports for capture, reassembly helpers, protocol detection, persistence, metrics, and timekeeping. Each adapter is packaged under `ca.gc.cra.radar.infrastructure.<area>` and may depend on external libraries (pcap4j, Kafka clients, OpenTelemetry).

## Package Overview
- `capture.{live,file,pcap}` — Packet sources and helpers for JNI libpcap, pcap4j, and file replay.
- `net` — Flow assemblers (`ReorderingFlowAssembler`, `PayloadFlowAssemblerAdapter`) and buffer pooling utilities.
- `protocol.{http,tn3270}` — Protocol modules binding detectors, reconstructors, and pairing engines.
- `persistence` — File/Kafka persistence adapters and `SegmentIoAdapter` utilities.
- `poster` — Poster pipelines and output adapters (file renderers).
- `metrics` — `OpenTelemetryMetricsAdapter`, bootstrap, and noop fallback.
- `buffer`, `time`, `exec` — Shared runtime utilities (buffer pools, monotonic clock, executor factories).

## Metrics
Adapters are responsible for emitting detailed metrics via `MetricsPort`, including:
- Flow assembler counters (`*.nullSegment`, `*.duplicate`, `*.buffered`).
- Protocol payload histograms (`protocol.http.bytes`, `protocol.tn3270.bytes`).
- Poster/persistence signals (`live.persist.*`, `assemble.pairing.error`).
Document any new metric in `docs/TELEMETRY_GUIDE.md` and ensure unit tests cover emission.

## Testing
- Adapters rely on integration-style tests with fake streams (`src/test/java/ca/gc/cra/radar/infrastructure/**`).
- JNI or external dependencies (libpcap, Kafka) should be wrapped with test doubles; see `KafkaSegmentRecordReaderAdapterTest` and `ReorderingFlowAssemblerTest` for patterns.

## Extending
- Follow hexagonal boundaries: adapters must only depend on domain/application ports.
- Guard external resources with try-with-resources and log structured errors (include `pipeline`, `protocol`, `sink`).
- Use buffer pools (`BufferPools`, `PooledBufferedOutputStream`) to keep GC pressure low.
- Validate configuration inputs early and surface actionable error messages.

