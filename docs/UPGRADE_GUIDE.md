# RADAR Upgrade Guide

## Versioning Policy
RADAR follows [Semantic Versioning](https://semver.org/). Patch releases contain bug fixes only, minor releases add backward-compatible features, and major releases may introduce breaking API changes. Each release updates `CHANGELOG.md` and tags the repository.

## Breaking Changes by Version
### Unreleased
- CLI validation tests now mute pipeline loggers; no user action required, but downstream tooling should expect quieter builds.

### 0.3.0
- TN3270 monitoring expanded with screen/session metrics and assembler updates.
- Flow assembler tightened for TN3270 screen mapping and event attribution.

### 0.2.0
- Capture adapters relocated under `ca.gc.cra.radar.infrastructure.capture.{live|file|pcap}`.
- Persistence workers transitioned to an executor with bounded queues; manual thread management hooks were removed.
- OpenTelemetry metrics adapter replaced the no-op implementation; pipelines require exporter configuration (`metricsExporter=otlp` or environment variables).
- Legacy assemblers and deprecated adapters removed.

### 0.1.0
- Initial baseline release with capture -> assemble -> sink pipeline supporting HTTP and TN3270.
- Poster pipelines, Kafka integration, offline pcaps, and Windows wpcap support introduced.

## Migration Steps
1. **Sync Dependencies**: Pull the latest `main` and align with the desired release tag.
2. **Update Imports**: Apply the package mapping table below to fix compile errors.
3. **Review Configuration**: Use `persistWorkers` / `persistQueueCapacity` knobs instead of custom persistence threads.
4. **Telemetry**: Configure OTLP exporters and update dashboards to use the `radar.metric.key` attribute.
5. **Rebuild Documentation**: Run `mvn -q -DskipTests=false verify` followed by `mvn -DskipTests=true javadoc:javadoc`.
6. **Smoke Test**: Execute an end-to-end workflow on representative traffic:
   ```bash
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar capture pcapFile=/path/sample.pcap out=./out --allow-overwrite
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar assemble in=./out out=./pairs
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar poster httpIn=./pairs/http httpOut=./reports/http
   ```
   Validate metrics (`capture.segment.persisted`, `assemble.pairs.persisted`), logs, and outputs before promoting.

## Package and API Mapping
| Legacy Location | Replacement |
| --- | --- |
| `ca.gc.cra.radar.capture.live.*` | `ca.gc.cra.radar.infrastructure.capture.live.*` |
| `ca.gc.cra.radar.capture.file.*` | `ca.gc.cra.radar.infrastructure.capture.file.*` |
| `ca.gc.cra.radar.capture.pcap.*` | `ca.gc.cra.radar.infrastructure.capture.pcap.*` |
| `ca.gc.cra.radar.assembler.LegacyHttpAssembler` | `ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler` + HTTP protocol module |
| `ca.gc.cra.radar.assembler.NoOpFlowAssembler` | `ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler` |
| Manual persistence threads | Configure `LiveProcessingUseCase` via `persistWorkers` / `persistQueueCapacity` |
| Custom metrics adapters | Implement `MetricsPort` and register via `CompositionRoot` |

## Metric Changes
- Metrics now flow through `MetricsPort` and `OpenTelemetryMetricsAdapter`, which sanitises instrument names and attaches `radar.metric.key`.
- Persistence shutdown signals moved from logs to metrics (`live.persist.shutdown.force`, `live.persist.shutdown.interrupted`). Update alerts accordingly.
- Flow assembly namespaces standardised to `live.*` and `assemble.*` prefixes followed by `.segment.accepted`, `.protocol.*`, `.pairs.generated`.

## Validation Checklist After Upgrade
- [ ] `mvn -q -DskipTests=false verify` passes (tests, Checkstyle, SpotBugs, JaCoCo).
- [ ] `mvn -DskipTests=true javadoc:javadoc` completes without warnings.
- [ ] End-to-end replay and live smoke tests produce expected outputs and metrics.
- [ ] Dashboards track new metrics (`capture.segment.persisted`, `live.persist.queue.depth`, `protocol.http.bytes`).
- [ ] Operators reviewed updated runbooks and telemetry docs; alerts reflect new metric names.
- [ ] `CHANGELOG.md` includes entries for your changes.

Report regressions with sample pcaps, sanitized logs, and metric snapshots. Coordinate with maintainers on mitigation for any breaking changes.
