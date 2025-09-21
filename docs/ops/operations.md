# RADAR Operations Runbook

This guide captures the day-to-day commands and guard rails needed to operate the RADAR
capture, assemble, and poster pipelines. Commands assume the distribution layout produced by
`mvn -q package` and default to file IO. Prefix commands with `java -jar radar-cli.jar` (or the
packaged launcher) when running outside the source tree.

## Quick start commands

### Capture CLI (file mode)
```
radar capture iface=ens3 bpf="tcp port 443" out=/var/radar/segments rollMiB=512
```
- Creates `/var/radar/segments` if missing and rolls files after ~512 MiB.
- Safe defaults: `snap=65535`, `bufmb=1024`, `timeout=1`, `promisc=true`, `immediate=true`.
- Add `ioMode=KAFKA kafkaBootstrap=broker:9092 kafkaTopicSegments=radar.segments` to stream
  segments directly to Kafka.

### Assemble CLI (file mode)
```
radar assemble in=/var/radar/segments out=/var/radar/pairs httpOut=/var/radar/http tnOut=/var/radar/tn3270
```
- Reads files emitted by capture, reconstructs protocol pairs, and persists HTTP/TN3270 outputs.
- Enable Kafka in/out with `ioMode=KAFKA kafkaBootstrap=broker:9092 kafkaSegmentsTopic=radar.segments`
  plus `kafkaHttpPairsTopic` / `kafkaTnPairsTopic`.

### Poster CLI (inspection run)
```
radar poster httpIn=/var/radar/pairs/http httpOut=/var/radar/poster/http decode=transfer
```
- Renders human-readable HTTP transactions using local pairs.
- Add `tnIn` / `tnOut` for TN3270, or switch to Kafka with `ioMode=KAFKA posterOutMode=KAFKA` and the
  corresponding topic flags.
- Use `decode=all` to inflate both transfer- and content-encoded payloads.

## Logging and exit codes

All CLIs write operator-facing messages to `stderr`. Fatal conditions propagate as non-zero exit
codes once the wrapper scripts are updated; use the matrix below as the canonical mapping when
wrapping the binaries today.

| Exit code | Meaning | Typical cause | Operator action |
|-----------|---------|---------------|-----------------|
| 0 | Success | Pipeline completed without errors | Rotate logs, archive outputs |
| 64 | Configuration error | Missing `iface`, Kafka bootstrap, or invalid path | Fix arguments; rerun |
| 65 | Input/output failure | Disk full, permission denied, Kafka unreachable | Verify environment, retry |
| 70 | Pipeline/runtime failure | Decoder exception, poster rendering bug | Capture logs, escalate to devs |
| 74 | External dependency failure | Downstream persistence (Kafka/S3) rejects writes | Confirm remote service, requeue |
| 130 | Interrupted by operator | CTRL+C or termination signal | Assess partial output before restart |

## Configuration highlights

### Capture CLI flags

| Flag | Default | Notes |
|------|---------|-------|
| `iface` | _required_ | Network interface name (e.g., `ens3`) |
| `bpf` | _none_ | BPF filter; validates via libpcap. Start with `tcp port 443` or `ip host x.x.x.x`. |
| `snap` | `65535` | Snap length bytes; keep at MTU+ for TLS. |
| `bufmb` | `1024` | Ring buffer size (MiB). Increase for high-throughput NICs. |
| `timeout` | `1` | Poll timeout (ms). Tune for latency vs CPU. |
| `promisc` | `true` | Disable only when SPAN mirrors specific VLANs. |
| `immediate` | `true` | Leave enabled for lowest latency. |
| `out` | `out/segments` | Directory for `.seg` files (file mode). |
| `ioMode` | `FILE` | Switch to `KAFKA` with `kafkaBootstrap` and `kafkaTopicSegments`. |
| `httpOut` / `tnOut` | `out/http`, `out/tn3270` | Poster staging directories for file mode. |

### Assemble CLI flags

| Flag | Default | Notes |
|------|---------|-------|
| `in` | _required_ | Capture segment directory or `kafka:<topic>`. |
| `out` | `out/pairs` | Root for produced NDJSON pairs. |
| `httpOut` / `tnOut` | `out/http`, `out/tn3270` | Override when splitting workloads. |
| `httpEnabled` | `true` | Disable to skip HTTP reconstructors. |
| `tnEnabled` | `false` | Enable when TN3270 capture required. |
| `ioMode` | `FILE` | Auto-switches to Kafka when topics provided. |
| `kafkaBootstrap` | _required for Kafka_ | Comma-separated brokers. |

### Poster CLI flags

| Flag | Default | Notes |
|------|---------|-------|
| `httpIn` / `tnIn` | _required per protocol_ | Input directories or Kafka topics. |
| `httpOut` / `tnOut` | _required for FILE outputs_ | Render destinations for poster artifacts. |
| `ioMode` | `FILE` | Governs input side (FILE vs KAFKA). |
| `posterOutMode` | `FILE` | Choose `KAFKA` to stream rendered reports. |
| `kafkaBootstrap` | _required for Kafka_ | Shared across all protocols. |
| `decode` | `none` | `transfer` decodes chunked encodings; `all` also decodes gzip/deflate. |

## Safe defaults, dry runs, and BPF gating

- **Safe start**: run capture in file mode with the defaults above to validate storage and NIC access
  before introducing Kafka. `rollMiB=256` limits blast radius on the first pass.
- **Dry-run workflow**: capture to a throwaway directory (e.g., `/tmp/radar/segments`), assemble to
  `/tmp/radar/pairs`, and run `poster` with outputs pointed at `/tmp/radar/poster`. Inspect artifacts
  before promoting the same command with production paths or Kafka topics.
- **BPF gating**: prefer explicit 5-tuples such as `bpf="ip host 10.8.10.5 and tcp port 23"`. Keep
  expressions short; invalid filters raise an error before capture starts.

## Performance tuning

- **Worker sizing**: capture is single-threaded but extremely sensitive to buffer pressure?increase
  `bufmb` and pin the process to dedicated CPUs when mirroring high-volume spans.
- **Assembler throughput**: split workloads by protocol (HTTP vs TN3270) and run separate `assemble`
  invocations when Kafka topics allow. This keeps flow reassembly lock-free.
- **Poster parallelism**: `PosterUseCase` already processes protocols in parallel. When publishing to
  Kafka, ensure the broker has sufficient partitions for the report topics to avoid back-pressure.
- **Queue choice**: use Kafka end-to-end for long-running deployments; file mode is ideal for short
  forensic bursts and simplifies dry runs.
- **GC and JVM flags**: prefer `-XX:+UseG1GC -Xms2g -Xmx2g -XX:MaxGCPauseMillis=200` for steady
  ingestion. Profile with JFR (`-XX:StartFlightRecording`) when tuning.

## Troubleshooting checklist

- **No packets captured**: confirm the interface is correct, check `sudo setcap cap_net_raw,cap_net_admin` on the binary,
  and validate the BPF filter with `tcpdump`.
- **Assembler exits with missing Kafka bootstrap**: supply `kafkaBootstrap` whenever any `kafka*Topic`
  flag is provided; both inputs and outputs share the same bootstrap list.
- **Poster produces empty files**: ensure `decode` matches captured payloads and confirm the `pairs`
  directories contain matching request/response pairs.
- **Permissions/IO errors**: run `radar capture` under a service account with write access to the
  output directories and verify free space with `df -h`.
- **Stale metrics**: restart the CLI after rotating logs?use shorter runs and external schedulers for
  continuous capture until the live orchestrator lands.
