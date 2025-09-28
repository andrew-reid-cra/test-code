# RADAR Operations Runbook

## Service Overview
RADAR captures TCP traffic, reconstructs protocol conversations (HTTP, TN3270), and writes structured outputs to files or Kafka. It exposes CLIs for offline capture (`capture`), live processing (`live`), assembly (`assemble`), and poster rendering (`poster`). Inputs are either network interfaces or `.pcap/.pcapng` files; outputs are rotated `.segbin` files plus protocol-specific blobs/indexes or Kafka topics.

## Deployment Profiles
| Profile | Usage | Recommended Resources |
| --- | --- | --- |
| Offline replay | Validate pipelines on archived pcaps using `capture pcapFile=...` or `assemble` only. | 4 vCPU, 8 GiB RAM, fast SSD for segment rotation. |
| Live capture (FILE) | Production sniffing with file persistence. | >=8 vCPU, 16 GiB RAM, NVMe storage sized for retention window. |
| Live capture (Kafka) | Stream segments and pairs to Kafka for downstream analytics. | >=8 vCPU, 16 GiB RAM, stable Kafka cluster with provisioned throughput. |
| Poster rendering | Generate human-readable reports from assembled outputs. | 4 vCPU, 8 GiB RAM, disk capacity for rendered artifacts. |

## Configuration Reference
| Setting | Description | How to Set | Default |
| --- | --- | --- | --- |
| `pcapFile` | Offline capture to replay. | CLI `pcapFile=/path/file.pcap`. | unset |
| `iface` | NIC to sniff (live pipelines). | CLI `iface=en0`. | `eth0` |
| `out` | Output directory for rotated segments. | CLI `out=/var/lib/radar/capture`. | `~/.radar/out/capture/segments` |
| `persistWorkers` | Live persistence executor size. | CLI `persistWorkers=8`. | `min(4, max(1, cores/2))` |
| `persistQueueCapacity` | Queue depth feeding persistence workers. | CLI `persistQueueCapacity=1024`. | `persistWorkers * 64` |
| `metricsExporter` | Metrics emission (`otlp` or `none`). | CLI `metricsExporter=otlp` or env `OTEL_METRICS_EXPORTER`. | `otlp` |
| `otelEndpoint` | OTLP collector endpoint. | CLI `otelEndpoint=http://collector:4317` or env `OTEL_EXPORTER_OTLP_ENDPOINT`. | unset |
| `otelResourceAttributes` | Custom OTel resource tags. | CLI `otelResourceAttributes=service.name=radar,deployment.environment=prod`. | unset |
| `--verbose` | Enable DEBUG logs. | CLI flag `--verbose`. | INFO |
| `kafkaBootstrap` | Kafka bootstrap servers (when `ioMode=KAFKA`). | CLI `kafkaBootstrap=broker:9092`. | required when Kafka mode |
| `persistQueueType` | Queue implementation (`ARRAY` or `LINKED`). | CLI `persistQueueType=ARRAY`. | `ARRAY` |
| `tn3270.emitScreenRenders` | Emit TN3270 `SCREEN_RENDER` events. | CLI `tn3270.emitScreenRenders=true`. | `false` |
| `tn3270.screenRenderSampleRate` | Fraction (0.0-1.0) of renders to emit when enabled. | CLI `tn3270.screenRenderSampleRate=0.25`. | `0.0` |
| `tn3270.redaction.policy` | Regex of TN3270 field labels to redact before emission. | CLI `tn3270.redaction.policy=(?i)^(SIN|ACC)$`. | `(?i)^(SIN)$` |

## Startup
1. Export telemetry environment variables if centralised metrics are required (see Telemetry Guide).
2. Prepare output directories or ensure Kafka topics exist.
3. Run the desired CLI, for example:
   ```bash
   java -jar target/RADAR-0.1.0-SNAPSHOT.jar live      iface=ens5 out=/var/lib/radar/capture      persistWorkers=8 persistQueueCapacity=2048      metricsExporter=otlp otelEndpoint=http://otel-collector:4317
   ```
4. Watch startup logs for `packet source started` (capture) or `live pipeline packet source started` messages.

## Shutdown
1. Send `SIGINT` (Ctrl+C) or SIGTERM to the JVM process.
2. Live pipelines flush queues and wait up to five seconds for persistence workers; logs announce `Live persistence executor shutdown initiated`.
3. Verify the absence of `live.persist.shutdown.force` increments; if present, inspect sinks for blocking calls.
4. Validate output directories for stray temporary files before deleting ephemeral disks.

## Health and Observability
Monitor these metrics (all exported via OpenTelemetry):
| Metric | Signal | Healthy Range | Alert When |
| --- | --- | --- | --- |
| `capture.segment.persisted` | Counter | Monotonic increase with traffic. | Stalls for >60s under expected load. |
| `live.persist.queue.highWater` | Gauge | < 70% of `persistQueueCapacity`. | > 80% for 3 consecutive samples. |
| `live.persist.enqueue.retry` | Counter | Near zero in steady state. | >1 per second; indicates saturation. |
| `live.persist.enqueue.dropped` | Counter | Always zero. | Any non-zero; triggers incident. |
| `live.persist.latencyNanos` | Histogram | Median < 2 ms, p95 < 10 ms. | p95 exceeds 20 ms for 5 minutes. |
| `live.persist.worker.uncaught` | Counter | Always zero. | Any increment; restart workers. |
| `protocol.http.bytes` / `protocol.tn3270.bytes` | Histogram | Reflects traffic shape. | Sudden drops/increases inconsistent with capture may indicate parser issues. |

Log fields include `pipeline`, `flowId`, `protocol`, and `sink` to aid tracing. All logs are structured via SLF4J/Logback.

## Runbook Scenarios
### Backpressure or Drops Rising
1. Check `live.persist.queue.highWater` and `live.persist.enqueue.retry` on dashboards.
2. Inspect logs for `Persist queue saturation` warnings.
3. Increase `persistWorkers` by 1-2 threads or enlarge `persistQueueCapacity` (maintaining `capacity >= workers`).
4. Validate sink latency; if downstream storage is slow, throttle capture or provision more sink capacity.
5. After tuning, confirm `live.persist.enqueue.dropped` remains zero.

### Sink Failures or Retries
1. Inspect `live.persist.error` and `live.persist.worker.uncaught` counters.
2. Review sink-specific logs (file exceptions, Kafka send failures) with correlation IDs.
3. For file sinks, ensure disks are writable and not full. For Kafka, verify brokers and authentication.
4. Restart pipeline if unrecoverable errors persist after addressing root cause.

### High GC or Allocation Pressure
1. Monitor JVM GC logs and CPU utilisation.
2. Confirm buffer pooling remains enabled (adapters use `BufferPool`); avoid disabling pooling in configs.
3. Reduce `persistWorkers` if CPU thrashes, or move to `persistQueueType=ARRAY` for better cache locality.
4. Consider increasing heap or enabling G1 tuning (`-XX:MaxGCPauseMillis=50`).

## Logs
- Default Logback config (`src/main/resources/logback.xml`) writes to console; pipe to a log collector in production.
- Use `--verbose` for DEBUG detail in transient troubleshooting windows.
- Sensitive payloads are not logged; packet contents remain on disk only. Never enable DEBUG long-term on production traffic.

## Security Posture
- Run live capture with the least privileges required to open the NIC (CAP_NET_RAW on Linux or run as a service with elevated rights).
- Store credentials (Kafka SASL, collector tokens) in environment variables or secret stores; do not hardcode.
- Custom BPF filters require `--enable-bpf`; enabling logs an explicit SECURITY warning.
- Scrub pcaps and outputs of sensitive data before sharing externally.

## FAQ / Troubleshooting
- **CLI rejects custom BPF:** Ensure `--enable-bpf` flag is present and the expression excludes prohibited characters (`;`, `` ` ``).
- **No metrics arrive at collector:** Confirm `metricsExporter=otlp`, `otelEndpoint=...`, and that firewalls allow outbound 4317/4318.
- **Kafka mode fails on startup:** Set `kafkaBootstrap` and a topic via `out=kafka:topic` or `kafkaTopicSegments=...`; topics must exist unless auto-creation is enabled.
- **Poster outputs empty files:** Verify `assemble` ran successfully and `protocol.http.bytes`/`protocol.tn3270.bytes` counters increased.
- **Queue depth stuck at zero with traffic present:** Check that the NIC has permissions and `capture.segment.persisted` is incrementing; if not, verify packet source logs for adapter errors.

