# RADAR Developer Guide

## Onboarding
- Install OpenJDK 17 or newer (validated up to Java 21) and set `JAVA_HOME`.
- Install Maven 3.9+; verify with `mvn -version`.
- Recommended IDEs: IntelliJ IDEA or Eclipse with Google Java Format, Checkstyle, and SpotBugs plugins.
- Clone the repository, import as a Maven project, and run `mvn -q -DskipTests=false verify` once to populate the local repository.

## Build and Test Workflow
| Goal | Command | Notes |
| --- | --- | --- |
| Full build, tests, quality gates | `mvn -q -DskipTests=false verify` | Runs unit/integration tests, JaCoCo, Checkstyle, SpotBugs, Surefire/Failsafe, Site reports. |
| Unit tests only | `mvn -q -pl :RADAR test` | Faster feedback for incremental changes. |
| Integration or benchmark suites | `mvn -Pbenchmarks test` | Optional profile for JMH and integration benchmarks. |
| Javadoc | `mvn -DskipTests=true javadoc:javadoc` | Ensures all public APIs have Javadoc without warnings. |
| Coverage report | `target/site/jacoco/index.html` | Keep overall coverage >=80% and critical logic at 100%. |

## Project Layout
```
src/main/java/ca/gc/cra/radar/
  domain/          # Immutable models and protocol-neutral logic
  application/     # Use cases + ports (capture, live, assemble, poster, telemetry)
  config/          # CLI configuration records and CompositionRoot wiring
  infrastructure/  # Adapter implementations (capture, protocol, persistence, metrics)
  adapter/         # External adapters (Kafka)
  api/             # CLI entry points, argument parsing, telemetry bootstrap
  logging/         # Logging helpers and Logback configuration support
  validation/      # Shared validators for CLI inputs
```
Generated artefacts and reports live under `target/`.

## Coding Standards
- Java SE 17+, Maven build, minimal dependencies; plan for future LTS releases.
- Hexagonal boundaries: domain/application remain framework-free; adapters implement ports for capture, protocols, sinks, telemetry.
- Immutability by default, defensive copies at module boundaries, thread-safe designs.
- SLF4J logging with contextual MDC fields (`pipeline`, `flowId`, `protocol`, `sink`); never use `System.out`/`System.err` for application logs.
- Concurrency via `java.util.concurrent` executors, queues, and atomics. Avoid blocking calls in hot loops and document executor sizing decisions.
- Observability is mandatory: wrap hot paths in spans, emit metrics via `MetricsPort`, and propagate context across executors.
- Security: validate every input, reject unsafe BPF expressions, never log secrets. Follow OWASP and CERT Java guidance.

## Adding Features
### New Sink Adapter (`PersistencePort`)
1. Implement `PersistencePort` (or `SegmentPersistencePort`). Honour batching (up to 32 pairs) and flush semantics.
2. Emit metrics consistent with existing namespaces (`live.persist.*`, `assemble.*`) and add unit tests covering success and failure paths.
3. Provide graceful shutdown (`close`, `flush`) and structured logging with MDC context.
4. Wire the adapter through `CompositionRoot` behind configuration switches.
5. Update module README, Telemetry Guide, and Ops Runbook with usage and observability notes.

### New Capture Strategy
1. Implement `PacketSource` and, if needed, a matching `FrameDecoder`.
2. Reuse buffer pools (`infrastructure.net.BufferPools`) to minimise allocations.
3. Integrate via `CaptureConfig`/`CompositionRoot` and expose CLI flags with validation.
4. Add integration tests using pcaps or synthetic frames; ensure telemetry covers errors and throughput.
5. Document new flags in README, Runbook, and Telemetry Guide.

### New Protocol Module
1. Add a `ProtocolId` and update `CompositionRoot` factories for reconstructor and pairing suppliers.
2. Implement `ProtocolModule`, `MessageReconstructor`, and `PairingEngine`, emitting protocol-specific metrics.
3. Extend assembler logic if out-of-band enrichment is required (for example TN3270 screen renders).
4. Update architecture diagrams, telemetry catalogues, and module READMEs with the new flow.

## Performance Tips
- Monitor `live.persist.queue.depth` and `live.persist.latencyNanos` during local tests; investigate regressions immediately.
- Reuse buffers and avoid per-segment allocations; prefer primitive arrays and pooled `ByteBuffer` instances.
- Keep hot loops branch-light; precompute constants and reuse `StringBuilder` or `ByteArrayOutputStream` instances.
- Profile with `perf`, `async-profiler`, or JMH suites before landing optimisations.

## Observability
- Metrics flow through `MetricsPort` to `OpenTelemetryMetricsAdapter`, which sanitises instrument names and adds the `radar.metric.key` attribute.
- Wrap new code paths with spans using `OpenTelemetryBootstrap` helpers or manual tracer usage; propagate `Context` across executors.
- Unit tests can inject fake metrics adapters to assert increments and observations.
- Update `docs/TELEMETRY_GUIDE.md` whenever metric names or units change.

## Local Testing & Tooling
- Use sample pcaps under `src/test/resources` or provide your own via `capture pcapFile=... --dry-run` to validate configuration.
- End-to-end smoke test:
  ```bash
  cp config/radar-example.yaml ./radar-dev.yaml

  java -jar target/RADAR-0.1.0-SNAPSHOT.jar capture --config=./radar-dev.yaml \
    pcapFile=fixtures/http_get.pcap \
    out=./tmp/capture \
    --allow-overwrite

  java -jar target/RADAR-0.1.0-SNAPSHOT.jar assemble --config=./radar-dev.yaml \
    in=./tmp/capture \
    out=./tmp/pairs

  java -jar target/RADAR-0.1.0-SNAPSHOT.jar poster --config=./radar-dev.yaml \
    httpIn=./tmp/pairs/http \
    httpOut=./tmp/reports/http
  ```
- Generate site reports (`mvn site`) and Javadoc before releasing.

## Pull Request Process
1. Follow the checklist in `CONTRIBUTING.md`.
2. Reference issues or tickets in the PR description and include relevant dashboards or benchmark deltas.
3. Document telemetry or operational impacts in the Ops Runbook and Telemetry Guide.
4. Request review only after `mvn verify` passes and documentation updates are committed.

Ship every change with production discipline: more secure, more observable, more maintainable.


