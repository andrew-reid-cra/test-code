# RADAR Telemetry Guide

## Enablement
Set OpenTelemetry environment variables (or CLI flags) before launching any CLI:
```bash
export OTEL_METRICS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
export OTEL_RESOURCE_ATTRIBUTES=service.name=radar,deployment.environment=dev,service.version=1.0.0
```
All CLIs honour inline overrides, for example `metricsExporter=otlp otelEndpoint=http://collector:4317`. Leaving the exporter unset disables metrics; production deployments must keep it enabled.

## Resource Attributes
`OpenTelemetryBootstrap` applies sensible defaults:
- `service.name=radar`
- `service.namespace=ca.gc.cra`
- `service.instance.id=<hostname>`
- `service.version` derived from Maven coordinates when available

Extend the resource with keys such as `deployment.environment`, `region`, or `pipeline` via `OTEL_RESOURCE_ATTRIBUTES=key=value,...`.

Every measurement includes the attribute `radar.metric.key=<original>` so dashboards can group on the unsanitised RADAR metric name even when exporter naming rules differ.

## Metric Catalogue
| Metric name | Type | Unit | Labels | Source / Notes |
| --- | --- | --- | --- | --- |
| `capture.segment.persisted` | Counter | 1 | `radar.metric.key` | Incremented when a segment is persisted by `SegmentCaptureUseCase`. |
| `capture.segment.skipped.decode` | Counter | 1 | `radar.metric.key` | Decoder could not map frame to TCP; monitor for malformed traffic. |
| `capture.segment.skipped.pureAck` | Counter | 1 | `radar.metric.key` | Pure ACK segments dropped to reduce storage. |
| `live.segment.skipped.decode` | Counter | 1 | `radar.metric.key` | Live decoder failed to produce a segment. |
| `live.segment.accepted` / `assemble.segment.accepted` | Counter | 1 | `radar.metric.key` | Segments accepted into the flow engine (prefix depends on pipeline). |
| `live.byteStream.bytes` / `assemble.byteStream.bytes` | Histogram | bytes | `radar.metric.key` | Byte volume emitted from the flow assembler per flush. |
| `live.protocol.detected` / `assemble.protocol.detected` | Counter | 1 | `radar.metric.key` | Protocol detected and reconstructor/pairing instantiated. |
| `live.protocol.disabled` / `assemble.protocol.disabled` | Counter | 1 | `radar.metric.key` | Detected protocol not enabled in configuration. |
| `live.protocol.unsupported` / `assemble.protocol.unsupported` | Counter | 1 | `radar.metric.key` | Missing reconstructor or pairing engine for detected protocol. |
| `live.flowAssembler.error` / `assemble.flowAssembler.error` | Counter | 1 | `radar.metric.key` | Flow assembler threw an exception. |
| `live.pairs.generated` / `assemble.pairs.generated` | Counter | 1 | `radar.metric.key` | Reconstructed message pairs produced by the flow engine. |
| `live.pairs.persisted` | Counter | 1 | `radar.metric.key` | Persistence worker flushed a batch successfully. |
| `live.persist.enqueued` | Counter | 1 | `radar.metric.key` | Batch enqueued to the persistence queue. |
| `live.persist.enqueue.retry` | Counter | 1 | `radar.metric.key` | Retry due to full queue (back-pressure). |
| `live.persist.enqueue.dropped` | Counter | 1 | `radar.metric.key` | Batch dropped after exceeding enqueue wait deadline. |
| `live.persist.enqueue.interrupted` | Counter | 1 | `radar.metric.key` | Enqueue interrupted by shutdown or failure. |
| `live.persist.worker.active` | Histogram | workers | `radar.metric.key` | Active worker count snapshot (0 during shutdown). |
| `live.persist.worker.interrupted` | Counter | 1 | `radar.metric.key` | Worker interrupted unexpectedly. |
| `live.persist.worker.uncaught` | Counter | 1 | `radar.metric.key` | Uncaught exception bubbled out of a worker thread. |
| `live.persist.error` | Counter | 1 | `radar.metric.key` | Persistence adapter reported an error. |
| `live.persist.flush.error` | Counter | 1 | `radar.metric.key` | Flush failed during shutdown. |
| `live.persist.shutdown.force` | Counter | 1 | `radar.metric.key` | Shutdown escalated to forced termination. |
| `live.persist.shutdown.interrupted` | Counter | 1 | `radar.metric.key` | Shutdown interrupted while waiting for workers. |
| `live.persist.queue.depth` | Histogram | items | `radar.metric.key` | Queue depth snapshot sampled each enqueue/dequeue. |
| `live.persist.queue.highWater` | Histogram | items | `radar.metric.key` | Maximum observed queue depth per run. |
| `live.persist.enqueue.waitNanos` | Histogram | ns | `radar.metric.key` | Raw enqueue wait duration. |
| `live.persist.enqueue.waitEmaNanos` | Histogram | ns | `radar.metric.key` | EMA of enqueue wait times. |
| `live.persist.latencyNanos` | Histogram | ns | `radar.metric.key` | Persistence round-trip latency per batch. |
| `live.persist.latencyEmaNanos` | Histogram | ns | `radar.metric.key` | EMA of persistence latency. |
| `assemble.pairs.persisted` | Counter | 1 | `radar.metric.key` | Offline assembler persisted a message pair. |
| `http.flowAssembler.bytes` / `http.flowAssembler.payload` | Histogram / Counter | bytes / 1 | `radar.metric.key` | HTTP payload metrics emitted by flow assembler adapters. |
| `tn3270.flowAssembler.bytes` / `tn3270.flowAssembler.payload` | Histogram / Counter | bytes / 1 | `radar.metric.key` | TN3270 payload metrics emitted by flow assembler adapters. |
| `tn3270.events.render.count` | Counter | 1 | `radar.metric.key`, `protocol=tn3270` | Screen render events emitted by `Tn3270AssemblerAdapter`. |
| `tn3270.events.submit.count` | Counter | 1 | `radar.metric.key`, `protocol=tn3270` | User submit events emitted by the assembler. |
| `tn3270.bytes.processed` | Counter | bytes | `radar.metric.key`, `protocol=tn3270` | Bytes processed after Telnet negotiation filtering. |
| `tn3270.parse.latency.ms` | Histogram | ms | `radar.metric.key`, `protocol=tn3270` | Parser latency per event. |
| `tn3270.sessions.active` | UpDownCounter | sessions | `radar.metric.key`, `protocol=tn3270` | Active session count tracked by the assembler. |

## Dashboards and Alerts
- **Throughput**: Plot `capture.segment.persisted`, `live.pairs.persisted`, and `protocol.*.bytes`. Alert on drops relative to baseline traffic.
- **Queue Health**: Track `live.persist.queue.depth`, `live.persist.queue.highWater`, and `live.persist.enqueue.retry`. Alert when depth exceeds 80% capacity or retries spike.
- **Latency**: Monitor `live.persist.latencyNanos` p50/p95; page when p95 >= 30 ms for five minutes.
- **Reliability**: Show single-value panels for `live.persist.worker.uncaught`, `live.persist.shutdown.force`, and `live.persist.error`. Any non-zero value demands investigation.
- **Protocol Mix**: Compare `protocol.http.bytes` vs `protocol.tn3270.bytes` to detect upstream traffic shifts.
- **Assembler Health**: Track `tn3270.events.*` counters and `tn3270.parse.latency.ms` to catch parser regressions.

## Testing Telemetry
1. Run a local collector (`otelcol`) or rely on the OpenTelemetry SDK testing utilities bundled with tests.
2. Launch a pipeline with metrics enabled, for example:
   ```bash
   cp config/radar-example.yaml ./radar-telemetry.yaml
   java -jar target/RADAR-1.0.0.jar capture --config=./radar-telemetry.yaml \
     pcapFile=fixtures/http_get.pcap \
     out=./tmp/capture \
     metricsExporter=otlp \
     otelEndpoint=http://localhost:4317
   ```
3. Verify the collector receives metrics with the `radar.metric.key` attribute. Group dashboards on that attribute for stable aggregation.
4. In unit tests, inject a fake `MetricsPort` to assert increments and observations for the relevant keys.
5. When introducing a new metric, update this guide, add unit tests covering success and failure paths, and adjust dashboards/alerts before merging.

Consistent telemetry is a release gate. Do not ship changes without updated documentation, tests, and monitoring assets.


