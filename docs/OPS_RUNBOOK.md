# RADAR Operations Runbook

## Service Overview
RADAR ingests TCP traffic, rebuilds HTTP and TN3270 conversations, and emits structured outputs to the filesystem or Kafka. Operators typically run one of three modes:
- `capture`: persist raw segments from live interfaces or archived pcaps.
- `live`: capture, assemble, and persist message pairs in a single process.
- `assemble` + `poster`: offline processing of previously captured segments into analyst-friendly artefacts.
Inputs are network interfaces or `.pcap/.pcapng` files; outputs are rotated segment binaries, protocol-specific files, or Kafka topics.

## Deployment Profiles
| Profile | Use Case | Recommended Resources |
| --- | --- | --- |
| Offline replay | Regression tests and investigations using archived pcaps. | 4 vCPU, 8 GiB RAM, SSD storage for segments and pairs. |
| Live capture (file sink) | On-device capture with file persistence. | >=8 vCPU, 16 GiB RAM, NVMe sized for retention window. |
| Live capture (Kafka sink) | Publish live pairs/events to Kafka. | >=8 vCPU, 16 GiB RAM, provisioned Kafka cluster and network egress. |
| Poster rendering | Generate human-readable reports from assembled pairs. | 4 vCPU, 8 GiB RAM, storage for rendered artefacts. |

## Configuration Reference
| Setting | Description | How to Set | Default |
| --- | --- | --- | --- |
| `pcapFile` | Offline capture source. | CLI `pcapFile=/path/trace.pcap`. | unset |
| `iface` | Live NIC to sniff. | CLI `iface=ens5`. | platform default (`eth0`) |
| `out` | Output directory or `kafka:<topic>`. | CLI `out=/var/lib/radar/capture` or `out=kafka:radar.segments`. | `~/.radar/out/...` |
| `ioMode` | `FILE` or `KAFKA` persistence mode. | CLI `ioMode=KAFKA`. | `FILE` |
| `kafkaBootstrap` | Kafka bootstrap servers. | CLI `kafkaBootstrap=broker:9092`. | required when `ioMode=KAFKA` |
| `persistWorkers` | Persistence executor thread count (live mode). | CLI `persistWorkers=8`. | `max(2, cores/2)` |
| `persistQueueCapacity` | Queue depth feeding persistence workers. | CLI `persistQueueCapacity=2048`. | `persistWorkers * 128` |
| `metricsExporter` | Metrics exporter (`otlp` or `none`). | CLI flag or `OTEL_METRICS_EXPORTER`. | `otlp` |
| `otelEndpoint` | OTLP endpoint URL. | CLI `otelEndpoint=http://collector:4317` or `OTEL_EXPORTER_OTLP_ENDPOINT`. | unset |
| `otelResourceAttributes` | Additional OTel resource attributes. | `OTEL_RESOURCE_ATTRIBUTES=service.name=radar,...`. | unset |
| `--verbose` | Elevate logging to DEBUG. | CLI `--verbose`. | `INFO` |
| `--dry-run` | Validate configuration without executing. | CLI `--dry-run`. | disabled |

## Startup Procedure
1. Export telemetry environment variables if metrics must reach a central collector (see Telemetry Guide).
2. Prepare output directories (create and set ownership) or confirm Kafka topics exist with required ACLs.
3. Launch the desired CLI, for example:
   ```bash
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar live \
     iface=ens5 \
     out=/var/lib/radar/live/pairs \
     persistWorkers=8 \
     persistQueueCapacity=2048 \
     metricsExporter=otlp \
     otelEndpoint=http://otel-collector:4317
   ```
4. Tail logs for `Capture packet source started`, `Live pipeline completed`, and the absence of WARN/ERROR entries.

## Shutdown Procedure
1. Send `SIGINT` (Ctrl+C) or `SIGTERM` to the JVM process.
2. The live pipeline drains queues and stops workers; expect `Live pipeline flushed persistence queue` in logs.
3. Monitor `live.persist.shutdown.force` and `live.persist.shutdown.interrupted`. Non-zero values indicate the drain timed out.
4. Confirm output directories contain only fully flushed files (no `.tmp` suffix) before removing storage.

## Health and Observability
Monitor via OpenTelemetry metrics:
| Metric | Type | Healthy Profile | Alert When |
| --- | --- | --- | --- |
| `capture.segment.persisted` | Counter | Monotonic growth with traffic. | Flat while interfaces report traffic. |
| `live.persist.queue.depth` | Histogram | <70% of capacity. | Sustained >80% for more than 1 minute. |
| `live.persist.queue.highWater` | Histogram | Gradual increase. | Reaches capacity or grows sharply. |
| `live.persist.enqueue.retry` | Counter | Near zero in steady state. | >5 per second for 5 minutes. |
| `live.persist.enqueue.dropped` | Counter | Always zero. | Any increment; treat as an incident. |
| `live.persist.latencyNanos` | Histogram | p95 < 20 ms. | p95 >= 30 ms for three consecutive windows. |
| `live.persist.worker.uncaught` | Counter | Zero. | Increment; inspect stack trace. |
| `live.persist.error` | Counter | Zero. | Increment; persistence adapter failed. |
| `assemble.pairs.persisted` | Counter | Monotonic during batch runs. | Stalls while job still running. |
| `protocol.http.bytes` | Histogram | Reflects HTTP payload volume. | Sudden drop under expected load. |
| `protocol.tn3270.bytes` | Histogram | Mirrors TN3270 traffic. | Unexpected spikes or zeros. |
| `tn3270.events.render.count` | Counter | Increases with TN3270 host writes. | Flat during known TN3270 activity. |

Logs include MDC fields such as `pipeline`, `flowId`, `protocol`, and `sink` for correlation across threads. Use dashboards to overlay queue depth, latency, and protocol volume trends.

## Runbook Scenarios
### Backpressure or Drops Rising
1. Review `live.persist.queue.depth`, `live.persist.queue.highWater`, and `live.persist.enqueue.retry` dashboards.
2. Inspect logs for `Persist queue saturation` warnings and downstream sink latency.
3. Increase `persistWorkers` or `persistQueueCapacity` (maintain `capacity >= workers * 64`) and redeploy.
4. If sinks are slow (disk or Kafka), scale downstream resources or throttle capture.
5. Confirm `live.persist.enqueue.dropped` remains zero after tuning.

### Sink Failures or Retries
1. Check `live.persist.error`, `live.persist.worker.uncaught`, and adapter-specific logs.
2. For file sinks, ensure paths are writable and have free space.
3. For Kafka sinks, verify broker availability, TLS/SASL credentials, and topic ACLs.
4. Restart the pipeline once the root cause is fixed.

### High GC or Allocation Pressure
1. Review JVM GC logs and CPU usage.
2. Confirm buffer pooling is enabled (do not disable `BufferPool` helpers).
3. Reduce `persistWorkers` or switch `persistQueueType` (`ARRAY` vs `LINKED`) as appropriate.
4. Tune the JVM (for example `-XX:MaxGCPauseMillis=50`, larger heap) after validating code-level efficiencies.

## Logs
- Default Logback configuration logs to stdout with structured fields; forward to central logging as required.
- Use `--verbose` sparingly for DEBUG investigations and revert to `INFO` in production.
- Sensitive payloads are never logged. Avoid long-term DEBUG logging on live traffic.

## Security Posture
- Live capture requires elevated privileges (CAP_NET_RAW on Linux). Use dedicated service accounts with least privilege.
- Provide credentials (Kafka, OTLP) via environment variables or secret stores; never commit secrets.
- Custom BPF filters require `--enable-bpf`. RADAR logs a SECURITY warning and truncates the expression to prevent injection.
- Sanitize packet captures and outputs before sharing outside trusted teams.

## FAQ / Troubleshooting
- **CLI rejects custom BPF**: Supply `--enable-bpf` and ensure the expression contains only printable ASCII without `;` or backticks.
- **No metrics in collector**: Verify `metricsExporter=otlp`, `OTEL_EXPORTER_OTLP_ENDPOINT`, and network egress to ports 4317/4318.
- **Kafka mode fails**: Provide `kafkaBootstrap` and a destination (`out=kafka:topic` or adapter-specific flags); confirm ACLs and topic existence.
- **Poster outputs empty files**: Run `assemble` first and confirm `assemble.pairs.persisted` increments; review logs for parsing errors.
- **Queue depth stuck at zero**: Check NIC permissions and confirm `capture.segment.persisted` increments; inspect capture logs for adapter issues.

Keep dashboards, alerts, and this runbook aligned with every deployment. Update thresholds whenever pipeline characteristics change.
