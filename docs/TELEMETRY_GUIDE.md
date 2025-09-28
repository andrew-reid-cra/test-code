# RADAR Telemetry Guide

## Enablement
Export OpenTelemetry environment variables before launching any CLI:
```bash
export OTEL_METRICS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
export OTEL_RESOURCE_ATTRIBUTES=service.name=radar,deployment.environment=dev,service.version=0.1.0-SNAPSHOT
```
All CLIs also accept inline overrides (for example, `metricsExporter=otlp otelEndpoint=http://collector:4317`). Leaving the exporter unset disables telemetry; production deployments must keep it enabled.

## Resource Attributes
The OpenTelemetry bootstrap applies sensible defaults:
- `service.name=radar`
- `service.namespace=ca.gc.cra`
- `service.instance.id=<hostname>`
- `service.version` derived from Maven/Git metadata when available.

Override or extend attributes (for example, `deployment.environment`, `region`, `pipeline`) via `OTEL_RESOURCE_ATTRIBUTES` or the matching CLI flag. Attributes are comma-separated key/value pairs using `=`.

## Metric Catalogue
Every measurement includes `radar.metric.key=<original>` to retain the unsanitized name regardless of exporter constraints. Use that attribute when building dashboards.

| Metric name | Type | Unit | Labels (attrs) | Source / Notes |
| --- | --- | --- | --- | --- |
| `capture.segment.persisted` | Counter | 1 | `radar.metric.key` | Incremented when a segment is written by `SegmentCaptureUseCase`. |
| `capture.segment.skipped.decode` | Counter | 1 | `radar.metric.key` | Decoder returned empty; indicates malformed frames or disabled protocols. |
| `capture.segment.skipped.pureAck` | Counter | 1 | `radar.metric.key` | Pure ACKs filtered to save storage. |
| `live.segment.accepted` | Counter | 1 | `radar.metric.key` | `FlowProcessingEngine` accepted a segment in live mode. |
| `live.byteStream.bytes` | Histogram | bytes | `radar.metric.key` | Observed when contiguous byte streams flush. |
| `live.pairs.generated` | Counter | 1 | `radar.metric.key` | Reconstructed message pairs produced before persistence. |
| `live.pairs.persisted` | Counter | 1 | `radar.metric.key` | Persistence worker successfully flushed a batch. |
| `live.persist.enqueued` | Counter | 1 | `radar.metric.key` | Message pairs placed on the bounded queue. |
| `live.persist.enqueue.retry` | Counter | 1 | `radar.metric.key` | Retry attempt after queue saturation. |
| `live.persist.enqueue.dropped` | Counter | 1 | `radar.metric.key` | Permanent drop after exceeding enqueue wait deadline. |
| `live.persist.enqueue.waitNanos` | Histogram | ns | `radar.metric.key` | Raw enqueue wait time per batch. |
| `live.persist.enqueue.waitEmaNanos` | Histogram | ns | `radar.metric.key` | Exponential moving average of enqueue wait. |
| `live.persist.queue.depth` | Histogram | items | `radar.metric.key` | Queue depth snapshot (treated as histogram/gauge). |
| `live.persist.queue.highWater` | Histogram | items | `radar.metric.key` | Maximum observed queue depth. |
| `live.persist.latencyNanos` | Histogram | ns | `radar.metric.key` | Persistence end-to-end latency per batch. |
| `live.persist.latencyEmaNanos` | Histogram | ns | `radar.metric.key` | EMA-smoothed persistence latency; track trends. |
| `live.persist.worker.active` | Histogram | workers | `radar.metric.key` | Active worker count (0 on shutdown). |
| `live.persist.worker.interrupted` | Counter | 1 | `radar.metric.key` | Worker interrupted unexpectedly. |
| `live.persist.worker.uncaught` | Counter | 1 | `radar.metric.key` | Uncaught exception bubbled from a worker thread. |
| `live.persist.flush.error` | Counter | 1 | `radar.metric.key` | Flush failure during shutdown. |
| `assemble.segment.accepted` | Counter | 1 | `radar.metric.key` | Offline assemble pipeline fed a segment into the flow engine. |
| `assemble.pairs.persisted` | Counter | 1 | `radar.metric.key` | Offline persistence wrote a message pair. |
| `assemble.byteStream.bytes` | Histogram | bytes | `radar.metric.key` | Byte volume emitted by offline assembly. |
| `assemble.pairing.error` | Counter | 1 | `radar.metric.key` | Pairing engine threw; monitor for protocol regressions. |
| `protocol.http.bytes` | Histogram | bytes | `radar.metric.key` | Total HTTP payload bytes processed. |
| `protocol.tn3270.bytes` | Histogram | bytes | `radar.metric.key` | Total TN3270 payload bytes processed. |
| `http.flowAssembler.payload` | Counter | 1 | `radar.metric.key` | Payload forwarded by HTTP flow assembler. |
| `http.flowAssembler.bytes` | Histogram | bytes | `radar.metric.key` | HTTP payload size per emission. |
| `tn3270.flowAssembler.payload` | Counter | 1 | `radar.metric.key` | TN3270 payload forwarded. |
| `tn3270.flowAssembler.bytes` | Histogram | bytes | `radar.metric.key` | TN3270 payload size per emission. |
| `tn3270.events.render.count` | Counter | 1 | `radar.metric.key` | TN3270 `SCREEN_RENDER` events emitted by the assembler. |
| `tn3270.events.submit.count` | Counter | 1 | `radar.metric.key` | TN3270 `USER_SUBMIT` events emitted by the assembler. |
| `tn3270.bytes.processed` | Counter | By | `radar.metric.key` | Filtered TN3270 bytes processed post Telnet negotiation. |
| `tn3270.parse.latency.ms` | Histogram | ms | `radar.metric.key` | Parser latency per TN3270 record. |
| `tn3270.sessions.active` | UpDownCounter | 1 | `radar.metric.key` | Active TN3270 sessions tracked by the assembler. |
| `<prefix>.protocol.disabled` | Counter | 1 | `radar.metric.key` | Flow detected protocol not enabled (prefix `live` or `assemble`). |
| `<prefix>.protocol.unsupported` | Counter | 1 | `radar.metric.key` | Recon/pairing factory missing for detected protocol. |
| `<prefix>.flowAssembler.error` | Counter | 1 | `radar.metric.key` | Flow assembler threw an exception. |
| `<prefix>.pairing.error` | Counter | 1 | `radar.metric.key` | Pairing engine threw during message correlation. |

## Dashboards and Alerts
- **Throughput**: Panel showing `capture.segment.persisted` rate and `protocol.*.bytes`. Alert if capture drops while NIC is healthy.
- **Queue Health**: Plot `live.persist.queue.depth` and `live.persist.queue.highWater`. Alert when high-water exceeds 80% of capacity or `live.persist.enqueue.retry` grows steadily.
- **Latency**: Histogram panel for `live.persist.latencyNanos` with p50/p95 overlays. Alert when p95 > 20 ms for sustained periods.
- **Errors**: Single-value widgets for `live.persist.worker.uncaught`, `<prefix>.flowAssembler.error`, and `<prefix>.protocol.unsupported`. Any non-zero should page operations.
- **Protocol Mix**: Compare HTTP vs TN3270 byte histograms to detect shifts in traffic composition.

## Testing Telemetry
1. Use `OpenTelemetryMetricsAdapter` with default OTLP settings or run a local collector via:
   ```bash
   otelcol --config otel-local.yaml
   ```
   with `otel-local.yaml` mirroring the sample in `docs/OPS_RUNBOOK.md`.
2. Run a CLI command with metrics enabled, e.g.:
   ```bash
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar capture      pcapFile=fixtures/http_get.pcap out=./tmp/capture      metricsExporter=otlp otelEndpoint=http://localhost:4317
   ```
3. Confirm the collector logs the expected metrics and attributes. Check that `radar.metric.key` is present so downstream dashboards can group on original names.
4. For unit tests, inject the in-memory test adapter (`TestMetricsAdapter` under `src/test/java`) and assert increments/observations on the relevant keys.
5. When adding new metrics, update this guide and add assertions ensuring the signal fires under both success and failure modes.

Consistent telemetry is mandatory: deploys must not merge unless dashboards reflect new metrics and alerts are reviewed.


