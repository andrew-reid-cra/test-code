# RADAR Application Layer

## Purpose
Coordinates hexagonal ports to execute RADAR pipelines: capture (`SegmentCaptureUseCase`), live processing (`LiveProcessingUseCase`), offline assembly (`AssembleUseCase`), and poster rendering (`PosterUseCase`).

## Ports
Interfaces under `ca.gc.cra.radar.application.port` define boundaries for:
- Capture inputs (`PacketSource`, `FrameDecoder`).
- Flow assembly (`FlowAssembler`, `ProtocolDetector`, `MessageReconstructor`, `PairingEngine`).
- Persistence (`SegmentPersistencePort`, `PersistencePort`).
- Observability (`MetricsPort`, `ClockPort`).

## Metrics
Use cases emit metrics via `MetricsPort`:
- Capture: `capture.segment.persisted`, `capture.segment.skipped.*`.
- Assemble: `assemble.segment.accepted`, `assemble.pairs.persisted`, `<prefix>.protocol.*`.
- Live: `live.persist.*`, `live.pairs.*`, queue depth and latency histograms.
Ensure new use cases follow the same naming convention and update the Telemetry Guide.

## Concurrency
- `LiveProcessingUseCase` owns the bounded persistence queue and executor (configured by `persistWorkers` and `persistQueueCapacity`).
- Other use cases remain single-threaded for deterministic ordering.

## Testing
- Application tests live in `src/test/java/ca/gc/cra/radar/application/**` and exercise use cases with fake ports.
- Use in-memory metrics adapters to assert telemetry behaviour.
- Cover success, backpressure, error propagation, and shutdown paths.

## Extending
- When adding a new pipeline, define its port interfaces first, then implement adapters.
- Register new pipelines in `CompositionRoot` and expose CLI wiring in `ca.gc.cra.radar.api`.
- Document contracts (preconditions, threading) in Javadoc so adapters implement them correctly.

