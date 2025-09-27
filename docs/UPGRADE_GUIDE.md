# RADAR Upgrade Guide

## Versioning Policy
RADAR follows [Semantic Versioning](https://semver.org/). Patch releases contain bug fixes only, minor releases introduce backward-compatible features, and major releases may include breaking API changes. Every release updates [CHANGELOG.md](../CHANGELOG.md).

## Breaking Changes by Version
### Unreleased
- Capture and persistence adapters moved under `ca.gc.cra.radar.infrastructure.capture` and `ca.gc.cra.radar.infrastructure.persistence` to clarify hexagonal boundaries.
- Live persistence workers now use a managed `ExecutorService`; any code depending on manual thread management must migrate to the new configuration knobs (`persistWorkers`, `persistQueueCapacity`).
- Legacy assemblers (`LegacyHttpAssembler`, `NoOpFlowAssembler`, `ContextualFlowAssembler`) were deleted. Integrations must bind to `FlowProcessingEngine` via registered `ProtocolModule`s.
- OpenTelemetry replaces the previous no-op metrics implementation. Pipelines require exporter configuration (`metricsExporter=otlp` or environment variables).

### 0.1.0
- Initial baseline release of the capture → assemble → sink pipeline with HTTP and TN3270 support.

## Migration Steps
1. **Update Dependencies**: Pull the latest `main` branch and ensure `pom.xml` aligns with the new release tag.
2. **Adopt New Packages**: Update imports to the new infrastructure locations listed below.
3. **Review Configuration**: Replace deprecated CLI flags with the new `persistWorkers`/`persistQueueCapacity` options if you previously tuned persistence threads manually.
4. **Telemetry**: Configure OTLP exporters (env vars or CLI) and update dashboards to consume `radar.metric.key` attributes.
5. **Regenerate Docs/Code**: Run `mvn -q -DskipTests=false verify` followed by `mvn -DskipTests=true javadoc:javadoc` to confirm API docs build cleanly.
6. **Functional Smoke Test**: Execute an end-to-end pipeline on representative traffic:
   ```bash
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar capture      pcapFile=/path/to/sample.pcap out=./out --allow-overwrite
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar assemble in=./out out=./pairs
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar poster httpIn=./pairs/http httpOut=./reports/http
   ```
   Validate metrics and outputs before promoting to higher environments.

## Package and API Mapping
| Legacy location | Replacement |
| --- | --- |
| `ca.gc.cra.radar.capture.live.*` | `ca.gc.cra.radar.infrastructure.capture.live.*` (libpcap and pcap4j packet sources). |
| `ca.gc.cra.radar.capture.file.*` | `ca.gc.cra.radar.infrastructure.capture.file.*` (pcap replay). |
| `ca.gc.cra.radar.capture.pcap.*` | `ca.gc.cra.radar.infrastructure.capture.pcap.*` (shared JNI bindings). |
| `ca.gc.cra.radar.assembler.LegacyHttpAssembler` | Use `ca.gc.cra.radar.infrastructure.protocol.http.HttpFlowAssemblerAdapter` + reconstructor/pairing factories. |
| `ca.gc.cra.radar.assembler.NoOpFlowAssembler` | Replaced by `ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler`. |
| Manual persistence threads | Configure `LiveProcessingUseCase` via `persistWorkers` and `persistQueueCapacity` (executor-managed). |

## Metrics Changes
- Metric emission now flows through `MetricsPort` → OpenTelemetry. The new adapter sanitizes instrument names and attaches `radar.metric.key`. Update any downstream dashboards to rely on that attribute instead of legacy metric names.
- Legacy log-only signals for persistence shutdown have been promoted to metrics (`live.persist.shutdown.force`, `live.persist.shutdown.interrupted`). Ensure alert rules monitor the metrics rather than parsing logs.

## Validation Checklist After Upgrade
- [ ] `mvn -q -DskipTests=false verify` passes with coverage thresholds intact.
- [ ] Integration tests replaying canonical pcaps succeed and produce outputs identical (or intentionally changed) compared to baseline snapshots.
- [ ] OpenTelemetry collector receives core metrics (`capture.segment.persisted`, `live.persist.queue.highWater`, `assemble.pairs.persisted`).
- [ ] Operators updated runbooks (see [docs/OPS_RUNBOOK.md](OPS_RUNBOOK.md)) and telemetry dashboards.
- [ ] CHANGELOG entry reflects new features and breaking changes for the release.

Report any regressions promptly; breaking changes require explicit documentation and migration aids in this guide.

