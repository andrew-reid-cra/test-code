# RADAR Upgrade Guide

## Versioning Policy
RADAR follows [Semantic Versioning](https://semver.org/). Patch releases contain bug fixes only, minor releases add backward-compatible features, and major releases may introduce breaking API changes. Each release updates [CHANGELOG.md](../CHANGELOG.md) and tags the repository.

## Breaking Changes by Version
### Unreleased
- Capture adapters consolidated under ca.gc.cra.radar.infrastructure.capture.{live|file|pcap}; update imports if you referenced legacy capture.* packages.
- Persistence workers transitioned to an executor with bounded queues; manual thread management hooks were removed.
- OpenTelemetry replaced the no-op metrics implementation. Pipelines require configuring exporters (metricsExporter=otlp or environment variables).
- Legacy assemblers (LegacyHttpAssembler, NoOpFlowAssembler, ContextualFlowAssembler) removed; integrate through FlowProcessingEngine with protocol modules.

### 0.1.0
- Initial baseline release with capture ? assemble ? sink pipeline supporting HTTP and TN3270.

## Migration Steps
1. **Sync Dependencies** ? Pull the latest main and align your fork with the release tag.
2. **Update Imports** ? Adjust source code to the new package layout using the mapping table below.
3. **Review Configuration** ? Replace custom persistence threading with persistWorkers / persistQueueCapacity flags.
4. **Telemetry** ? Configure OTLP exporters (environment variables or CLI flags) and update dashboards to consume the adar.metric.key attribute.
5. **Rebuild Documentation** ? Run mvn -q -DskipTests=false verify and mvn -DskipTests=true javadoc:javadoc to confirm site/javadoc still succeed.
6. **Smoke Test** ? Execute an end-to-end workflow on representative traffic:
   `ash
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar capture pcapFile=/path/sample.pcap out=./out --allow-overwrite
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar assemble in=./out out=./pairs
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar poster httpIn=./pairs/http httpOut=./reports/http
   `
   Validate metrics (capture.segment.persisted, ssemble.pairs.persisted), logs, and outputs before promoting.

## Package and API Mapping
| Legacy location | Replacement |
| --- | --- |
| ca.gc.cra.radar.capture.live.* | ca.gc.cra.radar.infrastructure.capture.live.* |
| ca.gc.cra.radar.capture.file.* | ca.gc.cra.radar.infrastructure.capture.file.* |
| ca.gc.cra.radar.capture.pcap.* | ca.gc.cra.radar.infrastructure.capture.pcap.* |
| ca.gc.cra.radar.assembler.LegacyHttpAssembler | ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler + HTTP protocol module wiring |
| ca.gc.cra.radar.assembler.NoOpFlowAssembler | ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler |
| Manual persistence threads | Configure LiveProcessingUseCase via persistWorkers / persistQueueCapacity |
| Custom metrics adapters | Implement MetricsPort and register via CompositionRoot if OTLP is unsuitable |

## Metric Changes
- Metrics now flow through MetricsPort and OpenTelemetryMetricsAdapter, which sanitizes instrument names and attaches adar.metric.key.
- Persistence shutdown signals moved from logs to metrics (live.persist.shutdown.force, live.persist.shutdown.interrupted). Update alerts accordingly.
- Flow assembly namespaces standardised to live.* and ssemble.* prefixes followed by .segment.accepted, .protocol.*, .pairs.generated.

## Validation Checklist After Upgrade
- [ ] mvn -q -DskipTests=false verify passes (tests, Checkstyle, SpotBugs, JaCoCo).
- [ ] mvn -DskipTests=true javadoc:javadoc succeeds without warnings.
- [ ] End-to-end replay and live smoke tests produce expected outputs and metrics.
- [ ] Dashboards track new metrics (capture.segment.persisted, live.persist.queue.depth, protocol.http.bytes).
- [ ] Operators reviewed updated runbooks and telemetry docs; alerts reflect new metric names.
- [ ] [CHANGELOG.md](../CHANGELOG.md) includes entries for your changes.

Report regressions with sample pcaps, logs, and metric snapshots (with secrets removed). Coordinate with maintainers on breaking change mitigations.
