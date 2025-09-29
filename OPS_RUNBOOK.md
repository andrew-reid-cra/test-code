# RADAR Ops Runbook

## Configuration Overview
- Manage configuration with a single YAML document (default: config/radar-example.yaml).
- Embedded defaults remain authoritative; omit keys you do not need.
- Precedence: **CLI overrides YAML**, and YAML overrides embedded defaults. Every CLI override logs a WARN so operators can spot drift.
- The same YAML supports capture, live, assemble, and poster modes; each mode reads common plus its own section.
- Keep secrets (Kafka credentials, collector auth) out of the file; supply them via environment variables or secret stores.

## Launch Examples
`bash
java -jar radar.jar capture --config=config/radar-example.yaml
java -jar radar.jar live --config=config/radar-example.yaml iface=ens5 persistWorkers=8
java -jar radar.jar assemble --config=config/radar-example.yaml ioMode=KAFKA kafkaBootstrap=kfk1:9092
java -jar radar.jar poster --config=config/radar-example.yaml posterOutMode=KAFKA decode=transfer
`

## Parameter Tables

Each table lists the YAML keys for that section. Blank defaults fall back to embedded logic.

### Common
| key | type | default | description | when to tune |
| --- | --- | --- | --- | --- |
| metricsExporter | enum (otlp\|none) | otlp | OpenTelemetry metrics exporter. | Set to 
one for air-gapped or perf experiments without telemetry. |
| otelEndpoint | string | "" | OTLP metrics endpoint URL. | Point at collector when defaults/env-vars are insufficient. |
| otelResourceAttributes | string | "" | Comma-separated resource attributes. | Add service.name, env, or ownership tags for observability hygiene. |
| verbose | boolean | false | Enables DEBUG logging pre-dispatch. | Temporarily enable while debugging CLI wiring; disable in production. |

### Capture
| key | type | default | description | when to tune |
| --- | --- | --- | --- | --- |
| iface | string | eth0 | Network interface for live capture. | Set per host to match traffic tap or AF_PACKET binding. |
| pcapFile | string | "" | Offline capture replay source. | Provide when replaying archived traces; keep empty for live capture. |
| protocol | enum (GENERIC\|TN3270) | GENERIC | Controls default BPF and downstream heuristics. | Switch to TN3270 when focusing on mainframe traffic. |
| protocolDefaultFilter.* | string | GENERIC: tcp / TN3270: tcp and (port 23 or port 992) | Default BPF per protocol hint. | Override when mainframe or custom services use non-standard ports. |
| snaplen | integer | 65535 | Packet snap length in bytes. | Lower to 4096/9216 when minimizing I/O; keep max for forensic fidelity. |
| bufmb | integer | 256 | libpcap buffer size (MiB). | Increase to 512/1024 on high-burst 40Gbps links to avoid drops. |
| timeout | integer | 1000 | Poll timeout in ms. | Drop to 0 for busy-poll when latency-sensitive and CPU is available. |
| promisc | boolean | false | Promiscuous mode toggle. | Enable only when switch mirror/SPAN requires it; otherwise leave off. |
| immediate | boolean | false | libpcap immediate mode. | Enable for lowest latency diagnostics; increases CPU churn. |
| out | path | ~/.radar/out/capture/segments | Segment output directory (FILE mode). | Point at NVMe or dedicated RAID when retention or throughput increases. |
| fileBase | string | segments | Prefix for rotated segment files. | Adjust to align with rotation tooling or naming conventions. |
| rollMiB | integer | 512 | Rotation threshold in MiB. | Raise on fast disks to reduce file handles; lower when tailing files frequently. |
| httpOut | path | ~/.radar/out/capture/http | HTTP staging directory. | Relocate when downstream assemble/poster run on different volume. |
| tnOut | path | ~/.radar/out/capture/tn3270 | TN3270 staging directory. | Align with storage layout for mainframe analysis. |
| ioMode | enum (FILE\|KAFKA) | FILE | Persistence strategy. | Switch to KAFKA when streaming segments to a cluster. |
| kafkaBootstrap | string | "" | Kafka bootstrap servers. | Mandatory when ioMode=KAFKA; use comma-separated host:port list. |
| kafkaTopicSegments | string | radar.segments | Segment topic name. | Customize per environment/tenant; keep sanitized. |
| persistWorkers | integer/blank | auto (min( cores/2, 4)) | Persistence worker threads. | Increase beyond auto when sinks are slow; decrease on small hosts. |
| persistQueueCapacity | integer/blank | auto (workers * 64) | Queue capacity feeding workers. | Raise when WARNs show queue saturation; keep bounded for memory hygiene. |
| persistQueueType | enum (ARRAY\|LINKED) | ARRAY | Persistence queue backing structure. | Use LINKED when workloads overflow array queue despite sizing. |
| tn3270.emitScreenRenders | boolean | false | Emit TN3270 screen render events. | Enable during investigations that require analyst-friendly screens. |
| tn3270.screenRenderSampleRate | double | 0.0 | Sample rate for screen render emission. | Increase (e.g., 0.1) when renders are needed but storage is limited. |
| tn3270.redaction.policy | string | "" | Regex matching TN3270 fields to redact. | Populate when regulatory policy mandates masking PII fields. |
| enableBpf | boolean | false | Safety flag gating custom BPF. | Must be true before setting bpf; keep false to enforce shipped filters. |
| bpf | string | "" | Custom BPF filter expression. | Only set after security review for targeted capture. |
| allowOverwrite | boolean | false | Permit reuse of non-empty directories. | Use for automation that rotates directories explicitly. |
| dryRun | boolean | false | Validate without executing capture. | Run during change windows to confirm IO/topology settings. |

### Live
Live mode honors the same capture keys; defaults mirror the capture table unless noted.
| key | type | default | description | when to tune |
| --- | --- | --- | --- | --- |
| iface | string | eth0 | Network interface for live processing. | Match NIC on production sensor hosts. |
| pcapFile | string | "" | Offline replay source (unsupported). | Keep empty—Live CLI rejects non-empty values. |
| protocol | enum | GENERIC | Default BPF hint. | Align with dominant protocol to trim noise. |
| protocolDefaultFilter.* | string | GENERIC: tcp / TN3270: tcp and (port 23 or port 992) | Default BPF per protocol hint. | Override when mainframe or custom services use non-standard ports. |
| snaplen | integer | 65535 | Capture snap length. | Lower only if downstream sinks cannot store full payloads. |
| bufmb | integer | 256 | Capture buffer MiB. | Scale with line-rate to avoid packet dropped counters. |
| timeout | integer | 1000 | Poll timeout ms. | Drop to 0 when throughput > latency. |
| promisc | boolean | false | Promiscuous capture. | Enable when necessary for mirrored traffic. |
| immediate | boolean | false | Immediate mode. | Enable for low latency debugging. |
| out | path | ~/.radar/out/capture/segments | Segment staging directory. | Point at fast storage even when ioMode=KAFKA for local failover. |
| fileBase | string | segments | Rotation prefix. | Align with ops naming. |
| rollMiB | integer | 512 | Rotation size MiB. | Scale with retention and downstream file watchers. |
| httpOut | path | ~/.radar/out/capture/http | HTTP staging. | Relocate when poster runs on remote host. |
| tnOut | path | ~/.radar/out/capture/tn3270 | TN staging. | Same as above. |
| ioMode | enum | FILE | Persistence mode. | Switch to KAFKA for centralized ingestion. |
| kafkaBootstrap | string | "" | Kafka bootstrap servers. | Required when using Kafka for segments. |
| kafkaTopicSegments | string | radar.segments | Segment topic. | Segregate per env/tenant. |
| persistWorkers | integer/blank | auto (min( cores/2, 4)) | Persistence worker threads. | Increase when WARNs show queue saturation; degrade when CPU bound. |
| persistQueueCapacity | integer/blank | auto (workers * 64) | Persistence queue slots. | Increase when dropping due to bursts. |
| persistQueueType | enum | ARRAY | Queue implementation. | Use LINKED when bursts overflow array queue despite sizing. |
| httpEnabled | boolean | true | Enables HTTP reconstruction/forwarding. | Disable when monitoring mainframe-only workloads. |
| tnEnabled | boolean | false | Enables TN3270 reconstruction. | Enable when TN traffic captured; disable otherwise to save CPU. |
| tn3270.emitScreenRenders | boolean | false | Screen render emission toggle. | Enable when analysts need live render artifacts. |
| tn3270.screenRenderSampleRate | double | 0.0 | Screen render sampling. | Increase gradually during investigations. |
| tn3270.redaction.policy | string | "" | TN3270 redaction regex. | Populate to mask mainframe fields. |
| enableBpf | boolean | false | Custom BPF gate. | Flip to true only when applying custom filters. |
| bpf | string | "" | Custom BPF. | Provide after enabling gate and performing security review. |
| allowOverwrite | boolean | false | Permit reuse of directories. | Use for orchestrated redeployments with known cleanup. |
| dryRun | boolean | false | Validation-only run. | Execute prior to maintenance windows to verify wiring. |

### Assemble
| key | type | default | description | when to tune |
| --- | --- | --- | --- | --- |
| in | path | ~/.radar/out/capture/segments | Segment input directory (FILE mode). | Point at capture output or mounted archive. |
| out | path | ~/.radar/out/assemble | Pair output root (FILE mode). | Relocate to storage sized for assembled pairs. |
| ioMode | enum (FILE\|KAFKA) | FILE | Segment input strategy. | Switch to KAFKA when consuming from streaming capture. |
| kafkaBootstrap | string | "" | Kafka bootstrap servers. | Mandatory for Kafka input/output. |
| kafkaSegmentsTopic | string | radar.segments | Segment topic. | Align with capture publisher topic. |
| kafkaHttpPairsTopic | string | radar.http.pairs | HTTP pairs topic. | Customize per environment. |
| kafkaTnPairsTopic | string | radar.tn3270.pairs | TN3270 pairs topic. | Customize per environment. |
| httpOut | string | "" (defaults to out/http) | HTTP pair directory override. | Set when storing HTTP pairs on dedicated volume. |
| tnOut | string | "" (defaults to out/tn3270) | TN3270 pair directory override. | Set when isolating TN outputs. |
| httpEnabled | boolean | true | HTTP reconstruction toggle. | Disable to skip HTTP processing for TN-only workloads. |
| tnEnabled | boolean | false | TN3270 reconstruction toggle. | Enable when TN traffic captured; disable to conserve cycles. |
| tn3270.emitScreenRenders | boolean | false | Emit screen render artifacts. | Turn on for investigations requiring screen context. |
| tn3270.screenRenderSampleRate | double | 0.0 | Render sampling rate. | Increase when more renders desired, keeping storage in mind. |
| tn3270.redaction.policy | string | "" | TN3270 field redaction regex. | Apply to satisfy privacy mandates. |
| allowOverwrite | boolean | false | Permit writing into non-empty directories. | Enable for pipelines that clean up between runs. |
| dryRun | boolean | false | Validation-only run. | Use to confirm Kafka/file wiring before full processing. |

### Poster
| key | type | default | description | when to tune |
| --- | --- | --- | --- | --- |
| ioMode | enum (FILE\|KAFKA) | FILE | Pair input strategy. | Switch to KAFKA when consuming assembled pairs from Kafka. |
| posterOutMode | enum (FILE\|KAFKA) | FILE | Poster sink strategy. | Use KAFKA to stream reports downstream. |
| httpIn | string | ~/.radar/out/assemble/http | HTTP pair input directory. | Override when assembling on another host. |
| tnIn | string | ~/.radar/out/assemble/tn3270 | TN3270 pair input directory. | Same as above. |
| httpOut | string | ~/.radar/out/poster/http | HTTP poster output directory. | Relocate when analysts consume from bespoke locations. |
| tnOut | string | ~/.radar/out/poster/tn3270 | TN3270 poster output directory. | Relocate for storage planning. |
| kafkaBootstrap | string | "" | Kafka bootstrap servers. | Required when any poster input/output uses Kafka. |
| kafkaHttpPairsTopic | string | radar.http.pairs | Kafka topic delivering HTTP pairs. | Set to match assembler publisher. |
| kafkaTnPairsTopic | string | radar.tn3270.pairs | Kafka topic delivering TN3270 pairs. | Set to match assembler publisher. |
| kafkaHttpReportsTopic | string | radar.http.reports | Kafka topic for rendered HTTP posters. | Customize per environment/consumer. |
| kafkaTnReportsTopic | string | radar.tn3270.reports | Kafka topic for rendered TN3270 posters. | Same as above. |
| decode | enum (none\|transfer\|all) | none | Poster decode depth. | Increase for analyst readability; remain at none for throughput. |
| allowOverwrite | boolean | false | Permit reuse of poster directories. | Enable for automation that purges directories between runs. |
| dryRun | boolean | false | Validation-only run. | Use to confirm IO wiring prior to batch rendering. |

## Validations & Troubleshooting
- **Kafka bootstrap required**: Any ioMode/posterOutMode set to KAFKA, or Kafka topics in use, must accompany kafkaBootstrap. CLI exits with Invalid configuration when missing—update YAML or CLI to include bootstrap servers.
- **Protocol toggles**: live and assemble require at least one of httpEnabled or 	nEnabled to be true. If both false, pipelines refuse to start. Re-enable the protocol you intend to process.
- **Custom BPF guard**: Providing bpf without enableBpf=true triggers a validation error. Flip the gate only after a security review.
- **File safety**: AllowOverwrite=false blocks writes into non-empty directories. Specify AllowOverwrite=true only when orchestration ensures safe reuse.
- **Poster FILE mode**: When posterOutMode=FILE, both httpOut and 	nOut must resolve to writable directories. YAML defaults point to ~/.radar/out/poster/**; adjust if those paths are absent.
- **Dry-run planning**: dryRun=true prints the execution plan and validates IO without producing output—useful for deployment pipelines and troubleshooting.

## Operational Tips
- Start from config/radar-example.yaml and layer environment-specific overrides in version control.
- Observe WARN logs for CLI overrides YAML: they highlight runtime hotfixes that should be folded back into YAML.
- Track key metrics: capture.segment.persisted, live.persist.queue.depth, Assemble.pairs.persisted, and poster latency histograms to confirm configuration changes are effective.
- When scaling to >30 Gbps, raise bufmb, consider 	imeout=0, and monitor queue metrics before increasing worker counts.
