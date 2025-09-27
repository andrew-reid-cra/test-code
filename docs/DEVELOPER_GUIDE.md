# RADAR Developer Guide

## Onboarding
- Install JDK 17 or newer (OpenJDK builds recommended) and ensure `JAVA_HOME` points to the distribution.
- Install Maven 3.9+; verify with `mvn -version`.
- Recommended IDE setup: IntelliJ IDEA or Eclipse with Google Java Format and Checkstyle plugins. Import the project as a Maven module.
- Clone the repository and run `mvn -q -DskipTests=false verify` once to populate the local repository and generate initial reports.

## Build and Test Workflow
| Goal | Command | Notes |
| --- | --- | --- |
| Full verify (tests + quality gates) | `mvn -q -DskipTests=false verify` | Runs unit/integration tests, JaCoCo, SpotBugs, Checkstyle. |
| Unit tests only | `mvn -q -pl :RADAR test` | Fast feedback on code changes (still JUnit 5). |
| Integration/perf suites | `mvn -Pbenchmarks test` | Optional profile for hot-path benchmarks (if enabled). |
| Generate Javadoc | `mvn -DskipTests=true javadoc:javadoc` | Output in `target/site/apidocs/index.html`. |
| Coverage report | `target/site/jacoco/index.html` | Inspect before merging to keep coverage ≥80% overall, 100% for critical logic. |

## Project Layout
```
src/main/java/ca/gc/cra/radar/
  domain/          # Immutable models (SegmentRecord, TcpSegment, MessagePair)
  application/     # Ports + pipeline use cases (capture, live, assemble, poster)
  config/          # CLI configuration records and CompositionRoot wiring
  infrastructure/  # Adapter implementations (capture, protocol, persistence, metrics)
  adapter/         # External adapter integrations (Kafka pipelines)
  api/             # CLI entrypoints, argument parsing, telemetry bootstrap
  logging/         # Logging configuration helpers (Logback, MDC utilities)
  validation/      # Shared validation utilities for CLI inputs
```
Generated artefacts and Maven reports live under `target/`.

## Coding Standards (Meta-Prompt Checklist)
- Java SE 17+, Maven for builds, minimal dependencies.
- Hexagonal architecture: keep business logic in domain/application; adapters implement ports.
- Immutability by default, no global mutable state, no deprecated APIs.
- SLF4J logging only; never use `System.out`. Include contextual fields (pipeline, flowId, protocol) via MDC where practical.
- Concurrency via `java.util.concurrent`; avoid bare threads and blocking calls in hot loops.
- Security: validate all inputs (`Strings`, `Numbers`, `Net` helpers), reject custom BPF without `--enable-bpf`, never log secrets.
- Observability: wrap hot paths with OpenTelemetry spans, increment metrics via `MetricsPort`, and propagate trace context across executors.

## Adding Features
### New Sink Adapter (PersistencePort)
1. Define a class implementing `PersistencePort` (or `SegmentPersistencePort` for raw segments).
2. Emit metrics (`metrics.increment/observe`) mirroring existing adapters (`live.persist.*`, protocol-specific counters).
3. Ensure resources are closed in `close()` and `flush()` handles partial batches.
4. Wire into `CompositionRoot` behind a config flag or mode.
5. Add unit tests with fakes (see `SegmentIoAdapterTest`, `KafkaPosterOutputAdapterTest`).
6. Document the adapter in module README and Telemetry Guide.

### New Capture Strategy
1. Implement `PacketSource` and, if necessary, a matching `FrameDecoder`.
2. Reuse buffer pools (`BufferPools`) to minimise allocations; surface queue metrics.
3. Register the adapter in `CompositionRoot` and expose CLI flags (update `CaptureConfig.fromMap`).
4. Add realistic integration tests using canned pcaps or synthetic streams.
5. Document CLI usage, metrics, and operational considerations.

## Performance Tips
- Reuse buffers via `infrastructure.buffer.BufferPool` and `PooledBufferedOutputStream`.
- Batch persistence operations (`LiveProcessingUseCase` already groups up to 32 pairs; align new sinks with that convention).
- Monitor `live.persist.latencyNanos` locally when experimenting; regression tests should assert no major regressions.
- Keep critical loops allocation-free; prefer pre-sized collections and primitive arrays.

## Observability
- Central entry point for metrics: `MetricsPort` (OpenTelemetry adapter lives in `infrastructure.metrics`).
- Wrap new code paths with spans using `OpenTelemetryBootstrap` helpers or manual instrumentation.
- Unit tests can use in-memory metrics collectors (see `LiveProcessingUseCaseTest` usage of fake metrics adapters).
- Update the Telemetry Guide with any new metric names or attributes.

## Local Testing & Tooling
- Use bundled fixtures in `src/test/resources` or your own pcaps via `capture pcapFile=... --dry-run` to validate configuration.
- For end-to-end smoke tests: run `capture` (pcap), `assemble`, and `poster` against a temp directory; inspect counters and outputs.
- Run `java -jar target/RADAR-0.1.0-SNAPSHOT.jar capture --help` for CLI summaries.

## Pull Request Process
1. Follow the checklist in [CONTRIBUTING.md](../CONTRIBUTING.md).
2. Link issues in commit messages or PR descriptions for traceability.
3. Attach `mvn verify` output, coverage summaries, and relevant dashboards in the PR.
4. Document telemetry changes and operational impacts in the Ops Runbook when applicable.

Stay vigilant about throughput, observability, and security; every change should leave the codebase more maintainable than before.

