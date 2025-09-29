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
| Live capture (file sink) | On-device capture with file persistence. | >=8 vCPU, 16 GiB RAM, NVMe sized for the retention window. |
| Live capture (Kafka sink) | Publish live pairs/events to Kafka. | >=8 vCPU, 16 GiB RAM, provisioned Kafka cluster and network egress. |
| Poster rendering | Generate human-readable reports from assembled pairs. | 4 vCPU, 8 GiB RAM, storage for rendered artefacts. |

## Configuration Reference
| Setting | Applies To | Description | How to Set | Default |
| --- | --- | --- | --- | --- |
| `pcapFile` | capture (offline) | Replay a PCAP/PCAPNG instead of listening to an interface. | YAML `capture.pcapFile`; override with CLI `pcapFile=/raid/traces/april12.pcap` | unset |
| `iface` | capture, live | Network interface used for packet capture. | YAML `capture.iface` / `live.iface`; override with CLI `iface=ens5` | platform default (`eth0`) |
| `ioMode` | capture, assemble, poster, live | Chooses directory (`FILE`) or Kafka (`KAFKA`) persistence. | YAML `<mode>.ioMode`; override with CLI `ioMode=KAFKA` | `FILE` |
| `out` | capture (FILE) | Directory that receives rotated segment binaries. | YAML `capture.out`; override with CLI `out=/var/lib/radar/capture/segments` | `~/.radar/out/...` |
| `in` | assemble | Segment input directory or topic (`in=kafka:radar.capture.segments`). | YAML `assemble.in`; override with CLI `in=/var/lib/radar/capture/segments` | required |
| `httpOut` / `tnOut` | assemble, poster | Override default protocol directories when writing to files. | YAML `<mode>.httpOut`; override with CLI `httpOut=/var/lib/radar/assemble/pairs/http` | derived under `out` |
| `posterOutMode` | poster | Poster sink: `FILE` writes directories, `KAFKA` streams renders. | YAML `poster.posterOutMode`; override with CLI `posterOutMode=KAFKA` | `FILE` |
| `kafkaBootstrap` | any Kafka mode | Bootstrap servers for Kafka IO (comma-separated). | YAML `<mode>.kafkaBootstrap`; override with CLI `kafkaBootstrap=broker1:9092,broker2:9092` | required when Kafka used |
| `kafkaTopicSegments` | capture (Kafka) | Topic that receives raw segments from capture. | YAML `capture.kafkaTopicSegments`; override with CLI `kafkaTopicSegments=radar.capture.segments` | derived from `out` when prefixed with `kafka:` |
| `kafkaSegmentsTopic` | assemble (Kafka) | Topic supplying captured segments to assemblers. | YAML `assemble.kafkaSegmentsTopic`; override with CLI `kafkaSegmentsTopic=radar.capture.segments` | derived from `in=` |
| `kafkaHttpPairsTopic` | assemble, poster (Kafka) | Topic for HTTP message pairs. | YAML `assemble.kafkaHttpPairsTopic`; override with CLI `kafkaHttpPairsTopic=radar.assemble.http` | sanitized from `out` |
| `kafkaTnPairsTopic` | assemble, poster (Kafka) | Topic for TN3270 message pairs. | YAML `assemble.kafkaTnPairsTopic`; override with CLI `kafkaTnPairsTopic=radar.assemble.tn3270` | sanitized from `out` |
| `kafkaHttpReportsTopic` | poster (Kafka) | Topic for rendered HTTP posters. | YAML `poster.kafkaHttpReportsTopic`; override with CLI `kafkaHttpReportsTopic=radar.poster.http` | required when `posterOutMode=KAFKA` |
| `kafkaTnReportsTopic` | poster (Kafka) | Topic for rendered TN3270 posters. | YAML `poster.kafkaTnReportsTopic`; override with CLI `kafkaTnReportsTopic=radar.poster.tn3270` | required when `posterOutMode=KAFKA` |
| `rollMiB` | capture, live | File rotation threshold in MiB. Use higher values on NVMe. | YAML `<mode>.rollMiB`; override with CLI `rollMiB=1024` | `512` |
| `snap` / `snaplen` | capture, live | Packet snap length in bytes; keep at 65535 for full payloads. | YAML `<mode>.snaplen`; override with CLI `snaplen=65535` | `65535` |
| `bufMb` | capture, live | Per-interface capture buffer in MiB. | YAML `<mode>.bufmb`; override with CLI `bufmb=1024` | `256` |
| `timeout` | capture, live | Poll timeout in milliseconds. Set `timeout=0` to busy-poll. | YAML `<mode>.timeout`; override with CLI `timeout=0` | `1000` |
| `persistWorkers` | live | Persistence worker threads for the live pipeline. | YAML `live.persistWorkers`; override with CLI `persistWorkers=16` | `max(2, cores/2)` |
| `persistQueueCapacity` | live | Queue depth feeding persistence workers. | YAML `live.persistQueueCapacity`; override with CLI `persistQueueCapacity=4096` | `persistWorkers * 128` |
| `persistQueueType` | live | Queue implementation (`ARRAY` or `LINKED`). | YAML `live.persistQueueType`; override with CLI `persistQueueType=ARRAY` | `ARRAY` |
| `httpEnabled` / `tnEnabled` | assemble | Toggle protocol-specific reconstruction. | YAML `assemble.tnEnabled`; override with CLI `tnEnabled=true` | `http=true`, `tn=false` |
| `tn3270.*` flags | assemble | Control TN3270 screen renders and redaction. | YAML `assemble.tn3270.emitScreenRenders`; override with CLI `tn3270.emitScreenRenders=true` | defaults from config |
| `decode` | poster | Poster decode mode (`none`, `transfer`, `all`). | YAML `poster.decode`; override with CLI `decode=transfer` | `none` |
| `--enable-bpf` / `--bpf` | capture, live | Allow custom BPF filters and set the expression. | Flags `--enable-bpf --bpf="tcp port 443"` | disabled |
| `--allow-overwrite` | all | Permit writing into non-empty directories. | YAML `<mode>.allowOverwrite`; override with CLI `--allow-overwrite` | disabled |
| `--dry-run` | all | Validate configuration without executing the pipeline. | YAML `<mode>.dryRun`; override with CLI `--dry-run` | disabled |
| `metricsExporter` | all | Metrics exporter (`otlp` or `none`). | YAML `common.metricsExporter`; override with CLI `metricsExporter=otlp` | `otlp` |
| `otelEndpoint` | all | OTLP metrics endpoint URL. | YAML `common.otelEndpoint`; override with CLI `otelEndpoint=http://collector:4317` | unset |
| `otelResourceAttributes` | all | Additional OTel resource attributes. | YAML `common.otelResourceAttributes`; override with `OTEL_RESOURCE_ATTRIBUTES=service.name=radar,...` | unset |
| `--verbose` | all | Enable DEBUG logging for investigations. | YAML `common.verbose`; override with CLI `--verbose` | `INFO` |


## Pipeline Deployment Modes
### File I/O (three-process mode)
Use this mode when the capture host cannot run heavy assembly/poster workloads or when you need replayable artefacts that can be reprocessed later.

```bash
# 1. Capture segments to disk
java -jar target/RADAR-1.0.0.jar capture --config=/etc/radar/capture.yaml \
  ioMode=FILE \
  iface=ens5 \
  out=/var/lib/radar/capture/segments \
  rollMiB=1024 snap=65535 bufMb=1024 timeout=0 \
  metricsExporter=otlp otelEndpoint=http://otel-collector:4317 \
  otelResourceAttributes=service.name=radar-capture,deployment.environment=prod

# 2. Assemble segments into message pairs
java -jar target/RADAR-1.0.0.jar assemble --config=/etc/radar/assemble.yaml \
  ioMode=FILE \
  in=/var/lib/radar/capture/segments \
  out=/var/lib/radar/assemble/pairs \
  httpEnabled=true tnEnabled=true \
  metricsExporter=otlp otelEndpoint=http://otel-collector:4317 \
  otelResourceAttributes=service.name=radar-assemble,deployment.environment=prod

# 3. Poster renders for analysts
java -jar target/RADAR-1.0.0.jar poster --config=/etc/radar/poster.yaml \
  ioMode=FILE \
  posterOutMode=FILE \
  httpIn=/var/lib/radar/assemble/pairs/http \
  httpOut=/var/lib/radar/poster/http \
  tnIn=/var/lib/radar/assemble/pairs/tn3270 \
  tnOut=/var/lib/radar/poster/tn3270 \
  metricsExporter=otlp otelEndpoint=http://otel-collector:4317 \
  otelResourceAttributes=service.name=radar-poster,deployment.environment=prod
```

High-throughput notes:
- `ioMode=FILE` on every process keeps intent explicit even when environment defaults change.
- For sustained 30+ Gbps capture, start with `rollMiB=1024`, `bufMb=1024`, and `timeout=0`; pin capture threads to isolated CPUs and enable RSS on the NIC.
- Keep capture, assemble, and poster directories on NVMe with >4 GB/s sustained writes; avoid NFS for hot paths.
- Run assembler and poster on separate hosts or containers once CPU usage exceeds ~70% to prevent back-pressure on capture.
- Use `--allow-overwrite` only for reprocessing and run `--dry-run` first to confirm directory wiring.

### Kafka (three-process mode)
Choose Kafka when multiple assemblers/posters need to consume the same traffic or when persistence should be remote.

```bash
# 1. Capture directly to Kafka topics
java -jar target/RADAR-1.0.0.jar capture --config=/etc/radar/capture.yaml \
  ioMode=KAFKA \
  iface=ens5 \
  out=/var/lib/radar/capture/segments \
  kafkaBootstrap=broker1:9092,broker2:9092 \
  kafkaTopicSegments=radar.capture.segments \
  rollMiB=512 snap=65535 bufMb=1024 timeout=0 \
  metricsExporter=otlp otelEndpoint=http://otel-collector:4317 \
  otelResourceAttributes=service.name=radar-capture,deployment.environment=prod

# 2. Assemble from the Kafka segment stream
java -jar target/RADAR-1.0.0.jar assemble --config=/etc/radar/assemble.yaml \
  ioMode=KAFKA \
  in=kafka:radar.capture.segments \
  kafkaBootstrap=broker1:9092,broker2:9092 \
  kafkaSegmentsTopic=radar.capture.segments \
  kafkaHttpPairsTopic=radar.assemble.http \
  kafkaTnPairsTopic=radar.assemble.tn3270 \
  out=/var/lib/radar/assemble/pairs \
  httpEnabled=true tnEnabled=true \
  metricsExporter=otlp otelEndpoint=http://otel-collector:4317 \
  otelResourceAttributes=service.name=radar-assemble,deployment.environment=prod

# 3. Poster pushes renders back to Kafka
java -jar target/RADAR-1.0.0.jar poster --config=/etc/radar/poster.yaml \
  ioMode=KAFKA \
  posterOutMode=KAFKA \
  kafkaBootstrap=broker1:9092,broker2:9092 \
  httpIn=kafka:radar.assemble.http \
  kafkaHttpReportsTopic=radar.poster.http \
  tnIn=kafka:radar.assemble.tn3270 \
  kafkaTnReportsTopic=radar.poster.tn3270 \
  metricsExporter=otlp otelEndpoint=http://otel-collector:4317 \
  otelResourceAttributes=service.name=radar-poster,deployment.environment=prod
```

High-throughput notes:
- Pre-create topics with at least one partition per capture thread and configure retention/compaction to match compliance requirements.
- Align Kafka producer properties such as `acks=all`, `linger.ms`, `batch.size`, and compression in `config/kafka.properties` to saturate brokers without overloading them.
- Place brokers and capture hosts on the same low-latency network segment; reserve bandwidth headroom for telemetry and control traffic.
- Monitor `capture.segment.persisted`, `assemble.pairs.persisted`, and poster render metrics to ensure downstream consumers keep up with capture volume.
- When scaling beyond 30 Gbps, deploy multiple assemblers/posters in parallel and shard by topic partitions; keep consumer lag <1 second.

### Process Flag Checklist
- **Capture**: `iface`, `ioMode`, `out`, `kafkaBootstrap`, `kafkaTopicSegments`, `rollMiB`, `snap`, `bufMb`, `timeout`, `--enable-bpf`.
- **Live**: `persistWorkers`, `persistQueueCapacity`, `persistQueueType`, `ioMode`, `out`/`kafkaTopicSegments`, `metricsExporter`, `otelEndpoint`.
- **Assemble**: `ioMode`, `in`, `out`, `kafkaBootstrap`, `kafkaSegmentsTopic`, `kafkaHttpPairsTopic`, `kafkaTnPairsTopic`, `httpEnabled`, `tnEnabled`, `tn3270.*`.
- **Poster**: `ioMode`, `posterOutMode`, `httpIn`, `tnIn`, `httpOut`, `tnOut`, `kafkaBootstrap`, `kafkaHttpReportsTopic`, `kafkaTnReportsTopic`, `decode`.
- Provide `kafkaBootstrap` whenever any `kafka*` flag is set, and use `--dry-run` to validate topic wiring before going live.

## Startup Procedure
1. Export telemetry environment variables if metrics must reach a central collector (see Telemetry Guide).
2. Prepare the output directory hierarchy or verify Kafka topics and ACLs.
3. Run the desired pipeline from the sections above. For live mode, start the `live` CLI with tuned flags.
4. Tail logs for `Capture packet source started`, `Live pipeline completed`, and the absence of WARN/ERROR entries.

## Shutdown Procedure
1. Send `SIGINT` (Ctrl+C) or `SIGTERM` to each JVM process.
2. Live pipelines drain the queue and stop workers; expect `Live pipeline flushed persistence queue` in logs.
3. Monitor `live.persist.shutdown.force` and `live.persist.shutdown.interrupted`. Non-zero values mean the drain timed out.
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

## Performance Tuning for 30+ Gbps
| Process | Critical Flags | 30 Gbps Starting Point | Watch This |
| --- | --- | --- | --- |
| Capture (file or Kafka) | `snap`, `bufMb`, `rollMiB`, `timeout`, `kafkaTopicSegments` | `snap=65535`, `bufMb=1024`, `rollMiB=1024`, `timeout=0`, RSS enabled, CPU pinning per NIC queue | `capture.segment.persisted`, pcap drop counters, NIC driver stats |
| Live pipeline | `persistWorkers`, `persistQueueCapacity`, `persistQueueType`, `ioMode`, `out`/`kafkaTopicSegments` | `persistWorkers` ~ physical cores, `persistQueueCapacity=persistWorkers*128`, `persistQueueType=ARRAY` | `live.persist.queue.depth`, `live.persist.enqueue.dropped`, GC pauses |
| Assemble | `ioMode`, `kafkaSegmentsTopic`, `httpEnabled`, `tnEnabled`, `httpOut`/`tnOut` | Dedicated host with NVMe, run 1 instance per 8 vCPU, keep `tnEnabled` disabled if not needed | `assemble.pairs.persisted`, assembler runtime, consumer lag |
| Poster | `posterOutMode`, `httpIn`/`tnIn`, `kafkaHttpReportsTopic`, `kafkaTnReportsTopic`, `decode` | Keep `decode=none` unless required, use Kafka output for shared consumers, ensure render directories on NVMe | Poster render latency metrics, topic lag |
| Kafka adapters | `linger.ms`, `batch.size`, `compression.type`, `acks` | `linger.ms=5`, `batch.size=131072`, `compression=snappy`, `acks=all`, partition count >= worker count | Producer error rate, broker throttle/ISR metrics |
| Host tuning | CPU pinning, NIC RSS/RPS, huge pages, IRQ affinity | Isolate capture cores, enable 8+ RX queues, reserve 75% memory for JVM heaps/buffers | CPU steal, `irqbalance`, memory pressure, disk latency |

Additional guidance:
- Enable NUMA-aware pinning (`taskset`, `numactl`) so capture threads and NIC interrupts share the same socket.
- Disable NIC power-management, checksum offload anomalies, and set MTU 9000 when the network supports jumbo frames.
- Pre-size JVM heaps (`-Xms` = `-Xmx`) and enable G1GC with `-XX:MaxGCPauseMillis=50`; consider `UseLargePages` on bare metal.
- Keep write-ahead directories on NVMe or striped SSD arrays delivering >4 GB/s sustained throughput; monitor filesystem saturation.
- Exercise the pipeline with replay tooling (e.g., `tcpreplay` or traffic generators) at 30-35 Gbps to validate headroom before onboarding production traffic.


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






