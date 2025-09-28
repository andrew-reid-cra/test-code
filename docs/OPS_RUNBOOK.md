# RADAR Operations Runbook

## Service Overview
RADAR ingests TCP traffic, reassembles HTTP and TN3270 conversations, and delivers structured outputs to file directories or Kafka topics. Operators can run it in three modes:
- capture ? persist raw segments from live interfaces or offline pcaps.
- live ? capture, assemble, and persist message pairs in a single process.
- ssemble + poster ? offline processing of captured segments into reports and downstream artefacts.
Inputs are network interfaces or .pcap/.pcapng files, and outputs are rotated segment binaries, protocol-specific files, or Kafka topics.

## Deployment Profiles
| Profile | Use Case | Recommended Resources |
| --- | --- | --- |
| Offline replay | Regression tests and investigations using archived pcaps. | 4 vCPU, 8 GiB RAM, SSD storage for segments/pairs. |
| Live capture (file sink) | On-device capture with file persistence. | ?8 vCPU, 16 GiB RAM, NVMe with capacity for retention window. |
| Live capture (Kafka sink) | Publish live pairs/events to Kafka. | ?8 vCPU, 16 GiB RAM, provisioned Kafka cluster and network egress. |
| Poster rendering | Generate human-readable reports from assembled pairs. | 4 vCPU, 8 GiB RAM, storage sized for rendered artefacts. |

## Configuration Reference
| Setting | Description | How to Set | Default |
| --- | --- | --- | --- |
| pcapFile | Offline capture source. | CLI pcapFile=/path/trace.pcap. | unset |
| iface | Live NIC to sniff. | CLI iface=ens5. | platform default (eth0). |
| out | Output directory or kafka:<topic>. | CLI out=/var/lib/radar/capture or out=kafka:radar.segments. | ~/.radar/out/... |
| ioMode | FILE or KAFKA persistence mode. | CLI ioMode=KAFKA. | FILE |
| kafkaBootstrap | Kafka bootstrap servers. | CLI kafkaBootstrap=broker:9092. | required when ioMode=KAFKA |
| persistWorkers | Persistence executor thread count (live mode). | CLI persistWorkers=8. | max(2, cores/2) |
| persistQueueCapacity | Queue depth feeding persistence workers. | CLI persistQueueCapacity=2048. | persistWorkers * 128 |
| metricsExporter | Metrics exporter (otlp or 
one). | CLI flag or OTEL_METRICS_EXPORTER. | otlp |
| otelEndpoint | OTLP endpoint URL. | CLI otelEndpoint=http://collector:4317 or OTEL_EXPORTER_OTLP_ENDPOINT. | unset |
| otelResourceAttributes | Extra OTel resource attributes. | OTEL_RESOURCE_ATTRIBUTES=service.name=radar,.... | unset |
| --verbose | Elevate logging to DEBUG. | CLI flag --verbose. | INFO |
| --dry-run | Validate configuration without executing. | CLI flag --dry-run. | disabled |

## Startup Procedure
1. Export telemetry environment variables if metrics must reach a central collector (see Telemetry Guide).
2. Prepare output directories (create and chown) or confirm Kafka topics exist with appropriate ACLs.
3. Launch the desired CLI, for example:
   `ash
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar live \
     iface=ens5 \
     out=/var/lib/radar/live/pairs \
     persistWorkers=8 \
     persistQueueCapacity=2048 \
     metricsExporter=otlp \
     otelEndpoint=http://otel-collector:4317
   `
4. Tail logs for Capture packet source started, Live pipeline completed, and absence of WARN/ERROR entries before handing off.

## Shutdown Procedure
1. Send SIGINT (Ctrl+C) or SIGTERM to the JVM process.
2. The live pipeline drains queues and shuts down workers. Expect logs for Live pipeline flushed persistence queue.
3. Watch the metrics live.persist.shutdown.force and live.persist.shutdown.interrupted; non-zero values indicate the drain timed out.
4. Confirm output directories contain fully flushed files (no .tmp suffix) before unmounting disks.

## Health and Observability
Monitor via OpenTelemetry metrics:
| Metric | Type | Healthy Profile | Alert When |
| --- | --- | --- | --- |
| capture.segment.persisted | Counter | Monotonic growth with traffic. | Flat while interfaces report traffic. |
| live.persist.queue.depth | Histogram | <70% of capacity. | Sustained >80% over 1 minute. |
| live.persist.queue.highWater | Histogram | Moves slowly; stays below capacity. | Reaches capacity or grows rapidly. |
| live.persist.enqueue.retry | Counter | Near zero in steady state. | >5 per second for 5 minutes. |
| live.persist.enqueue.dropped | Counter | Always zero. | Any increase; triggers incident. |
| live.persist.latencyNanos | Histogram | p95 < 20 ms. | p95 ? 30 ms for 3 consecutive windows. |
| live.persist.worker.uncaught | Counter | Zero. | Increment; investigate stacktrace. |
| live.persist.error | Counter | Zero. | Increment; indicates persistence failure. |
| ssemble.pairs.persisted | Counter | Monotonic growth during batch runs. | Stalls while job still running. |
| protocol.http.bytes | Histogram | Reflects HTTP payload volume. | Sudden drop under expected load. |
| protocol.tn3270.bytes | Histogram | Mirrors TN3270 traffic. | Unexpected spikes or zeros. |
| 	n3270.events.render.count | Counter | Increases with TN3270 host writes. | Flat during known TN3270 activity. |

Log context includes pipeline, lowId, protocol, and sink MDC keys for correlation. Use dashboards to overlay queue depth, latency, and traffic volume.

## Runbook Scenarios
### Backpressure or Drops Rising
1. Inspect live.persist.queue.depth, live.persist.queue.highWater, and live.persist.enqueue.retry dashboards.
2. Examine logs for Persist queue saturation warnings and downstream sink latency.
3. Increase persistWorkers or persistQueueCapacity (maintain capacity ? workers * 64) and redeploy.
4. If sinks are slow (disk IO or Kafka), scale downstream resources or throttle capture.
5. Confirm live.persist.enqueue.dropped remains zero after tuning.

### Sink Failures or Retries
1. Check live.persist.error, live.persist.worker.uncaught, and adapter-specific logs.
2. For file sinks, ensure output directories remain writable and have free space.
3. For Kafka sinks, verify broker availability, TLS/SASL credentials, and topic ACLs.
4. Restart the pipeline once the root cause is resolved.

### High GC or Allocation Pressure
1. Review JVM GC logs and CPU usage.
2. Confirm buffer pooling remains enabled (do not disable BufferPool helpers).
3. Reduce persistWorkers or switch persistQueueType (Array vs Linked) depending on CPU/memory characteristics.
4. Consider tuning the JVM (-XX:MaxGCPauseMillis=50, larger heap) after confirming code-level efficiencies.

## Logs
- Default Logback configuration logs to stdout with structured fields; forward to your logging stack.
- Use --verbose sparingly for DEBUG investigations; revert to INFO in production.
- Sensitive payloads are never logged. Do not enable DEBUG long term on live traffic.

## Security Posture
- Live capture requires elevated privileges (CAP_NET_RAW on Linux). Prefer dedicated service accounts with least privilege.
- Provide credentials (Kafka, OTLP) via environment variables or secret stores. Never commit them to source control.
- Custom BPF filters need --enable-bpf. RADAR logs a SECURITY warning and truncates the expression to prevent injection.
- Sanitize packet captures and outputs before sharing outside trusted teams.

## FAQ / Troubleshooting
- **CLI rejects custom BPF** ? Ensure --enable-bpf precedes --bpf="expression" and the expression contains only printable ASCII without ; or backticks.
- **No metrics in collector** ? Verify metricsExporter=otlp, OTEL_EXPORTER_OTLP_ENDPOINT, and network egress to ports 4317/4318.
- **Kafka mode fails** ? Set kafkaBootstrap and a Kafka destination (out=kafka:topic or adapter-specific topic flags). Confirm ACLs and topic existence.
- **Poster outputs empty files** ? Run ssemble first and confirm ssemble.pairs.persisted increments; check logs for parsing errors.
- **Queue depth stuck at zero** ? Validate NIC permissions and that capture.segment.persisted increases; inspect capture logs for adapter errors or offline sources.

Keep this runbook and dashboards synchronized with every deployment. Update metrics thresholds when pipeline characteristics change.
