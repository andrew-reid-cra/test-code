# RADAR Observability Guide (OpenTelemetry)

> **Goal:** Enterprise-grade visibility for performance and correctness.  
> **Scope:** All modules (Capture, Assembler, Sink) MUST emit **metrics**, **traces**, and **structured logs** via **OpenTelemetry (OTel)**.  
> **Outcomes:** Low-overhead, production-safe telemetry with actionable signals for **40 Gbps** sustained throughput.

---

## 1) OTel Foundations

### 1.1 Required Resource Attributes
All processes **must** initialize an OTel `Resource` with:

- `service.name=radar-<module>` (e.g., `radar-capture`, `radar-assembler`, `radar-sink`)  
- `service.version=<git-version-or-semver>`  
- `service.namespace=radar`  
- `deployment.environment=<dev|qa|prod>`  
- `host.name`, `os.type`, `process.pid` (allow defaults)

### 1.2 Export & Wire Format
- **OTLP over gRPC** (preferred) or HTTP to your collector.  
- Avoid direct-to-backend exporters in app code; **export to a collector** for resiliency and throttling.

### 1.3 Naming Conventions
- **Meter name:** `ca.gc.cra.radar.<module>`  
- **Tracer name:** `ca.gc.cra.radar.<module>` (one per module or per package)  
- **Metric names:** `radar.<module>.<noun>[.<verb>]` (lowercase, dot-separated), units as UCUM.  
  - Examples: `radar.capture.packets` (1), `radar.capture.bytes` (By), `radar.sink.latency` (ms)

### 1.4 Performance Principles
- Prefer **asynchronous** (observable) gauges for queue depth, pool utilization.  
- Use **Histograms** for latency/size distributions.  
- Add **Attributes** sparingly; bound cardinality (e.g., protocol family, adapter).  
- Enable **exemplars** to link metrics to traces where supported.

---

## 2) Module Telemetry Specifications

### 2.1 Capture Module (NIC ??? Segment Queue)
**Attributes (common):**  
`adapter={jnr-libpcap|pcap4j}`, `if_name`, `protocol={tcp|udp|other}`, `queue=segment`

**Counters**
- `radar.capture.packets` (1) ??? total packets captured  
- `radar.capture.bytes` (By) ??? total bytes captured  
- `radar.capture.dropped` (1) ??? kernel/driver drops (if available)  
- `radar.capture.errors` (1) ??? capture errors (timeouts, syscalls, adapter faults)

**Histograms**
- `radar.capture.batch.size` (1) ??? packets per poll/batch  
- `radar.capture.poll.latency` (ms) ??? time in poll/read calls

**Observable Gauges**
- `radar.capture.queue.depth` (1) ??? pending segments in queue  
- `radar.capture.throughput` (By/s) ??? rolling byte rate (calculate in code)
- Attributes: tag `impl` (jni|pcap4j) plus `source` (iface or file) so dashboards distinguish adapters.

**Traces**
- **Span:** `capture.poll` around each poll/batch  
  - Attributes: `batch.size`, `adapter`, `if_name`  
  - Events on anomalies: `drop`, `timeout`, `buffer_overrun`

**Logs**
- Level **WARN/ERROR** for adapter failures with `adapter`, `if_name`, `errno`, `consecutive_failures`.

---

### 2.2 Assembler Module (Segment Queue ??? Flow/Message)
**Attributes (common):**  
`protocol={http|tn3270|other}`, `queue={segment|flow}`, `reassembly={inorder|reorder}`, `buffer_pool={on|off}`

**Counters**
- `radar.assembler.sessions.started` (1)  
- `radar.assembler.sessions.terminated` (1)  
- `radar.assembler.messages.reassembled` (1) ??? HTTP msgs or TN3270 transactions  
- `radar.assembler.reassembly.errors` (1) ??? checksum, gaps, timeouts  
- `radar.assembler.tcp.retransmissions.seen` (1) ??? if available

**Histograms**
- `radar.assembler.reassembly.latency` (ms) ??? segment???message latency  
- `radar.assembler.message.size` (By) ??? payload size distribution

**Observable Gauges**
- `radar.assembler.sessions.active` (1)  
- `radar.assembler.queue.depth` (1) ??? inflight segments/messages  
- `radar.assembler.buffer.pool.inuse` (By)

**Traces**
- **Span:** `assembler.reassemble` per message/flow  
  - Link to capture span via **span link** or **context propagation** (see ??4).  
  - Events: `gap_detected`, `reorder`, `timeout_eviction`

**Logs**
- Structured **INFO** when switching protocol parsers; **WARN** for recoverable parse errors; **ERROR** for corrupt sequences dropped.

---

### 2.3 Sink Module (Message ??? Persistence/Outbound)
**Attributes (common):**  
`sink={opensearch|file|kinesis|http}`, `queue={message|persist}`

**Counters**
- `radar.sink.records.posted` (1)  
- `radar.sink.records.failed` (1)  
- `radar.sink.retries` (1)  

**Histograms**
- `radar.sink.latency` (ms) ??? request???ack time  
- `radar.sink.payload.size` (By)

**Observable Gauges**
- `radar.sink.queue.depth` (1)  
- `radar.sink.endpoint.rtt` (ms) ??? rolling estimate if feasible

**Traces**
- **Span:** `sink.persist` or `sink.post`  
  - Attributes: `sink.type`, `endpoint`, `http.status_code` (if HTTP)  
  - Events: `retry_scheduled`, `backoff`, `circuit_open` (if using CB)

**Logs**
- **WARN** for transient failures with retry metadata; **ERROR** with correlation IDs on terminal failures.

---


### 2.3.1 Live Persistence Executor (LiveProcessingUseCase)
**Counters**
- live.persist.enqueued (1)  - message pairs handed to the executor
- live.persist.enqueue.retry (1)  - retries attempted while the queue was saturated
- live.persist.enqueue.dropped (1)  - pairs dropped after exceeding enqueue timeout
- live.persist.error (1)  - persistence failures surfaced to the coordinator
- live.persist.worker.uncaught (1)  - worker-level failures (caught or uncaught)
- live.persist.worker.interrupted (1)  - interruptions observed outside graceful shutdown
- live.persist.shutdown.force (1)  - forced executor shutdowns
- live.persist.shutdown.interrupted (1)  - interruptions while awaiting graceful stop

**Observable Gauges**
- live.persist.worker.active (1)  - active executor threads
- live.persist.queue.depth (1)  - current bounded queue depth

**Histograms**
- live.persist.latencyNanos (ns)  - persistence latency
- live.persist.enqueue.waitNanos (ns)  - enqueue wait time

**Notes**
- Metrics share the 
adar.metric.key attribute so dashboards can differentiate live persistence from offline assemble sinks.
- Saturation events also emit WARN logs summarizing queue depth and retry counts.


## 3) Java Implementation Patterns

### 3.1 Bootstrapping the OTel SDK (OTLP)
```java
// package ca.gc.cra.radar.telemetry;
... Java code here ...
```

### 3.2 Defining Instruments (Capture Example)
```java
// package ca.gc.cra.radar.capture;
... Java code here ...
```

### 3.3 Tracing with Context Across Queues
```java
// package ca.gc.cra.radar.core;
... Java code here ...
```

### 3.4 Structured Logs via SLF4J with Trace IDs
- Use a logging layout that includes `trace_id` and `span_id` (via MDC or OTel autoinstrumentation bridge).  
- Log **key/value** pairs (JSON if supported) to correlate with metrics/traces.  
- Do **not** log packet contents or PII unless redacted and approved.

---

## 4) Testing Telemetry

### 4.1 Unit Tests (SDK In-Memory)
- Use **`InMemoryMetricReader`** and **in-memory span exporter** to assert:

### 4.2 Integration/Perf Tests
- Drive synthetic traffic through **Capture ??? Assembler ??? Sink** and validate:
...
### 4.3 Local Collector Smoke Test
- **Prerequisites:** download the OpenTelemetry Collector binary that matches your OS from the official releases page (https://github.com/open-telemetry/opentelemetry-collector-releases).
- **Configuration:** save the following as `otel-local.yaml` to receive metrics from RADAR and log them locally:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"
exporters:
  logging:
    loglevel: debug
service:
  pipelines:
    metrics:
      receivers: [otlp]
      exporters: [logging]
```

- **Run the collector:**
  ```bash
  otelcol --config otel-local.yaml
  ```
- **Invoke RADAR with metrics enabled (choose the CLI under test):**
  ```bash
  java -cp target/RADAR-0.1.0-SNAPSHOT.jar \
    ca.gc.cra.radar.api.Main capture \
    iface=en0 metricsExporter=otlp \
    otelEndpoint=http://localhost:4317 \
    otelResourceAttributes=service.name=radar-capture,deployment.environment=dev
  ```
  - Alternate: export `OTEL_METRICS_EXPORTER`, `OTEL_EXPORTER_OTLP_ENDPOINT`, and `OTEL_RESOURCE_ATTRIBUTES` before invoking the CLI so every module shares the same config.
- **Trigger activity:** replay a PCAP or run a short capture/assemble/poster cycle so counters and histograms emit at least once.
- **Verify output:** the collector terminal should display metric points such as `capture.segment.persisted` or `live.persist.latencyNanos` with the `radar.metric.key` attribute and the expected resource attributes (`service.name`, `service.namespace`, etc.).
- **Promote the setup:** once the smoke test passes, repoint `otelEndpoint` (or the `OTEL_EXPORTER_OTLP_ENDPOINT` env var) to your staging collector and repeat the validation.




---

## 5) Alerting & SLO Hints
...

---

## 6) Maven Coordinates (example)
...

---

## 7) Developer Checklist (Observability)
...

---

## 8) Dashboard Seeds (quick start)
...

---

## 9) Security & Privacy
...

---

## 10) Migration Notes (if you already have logs/metrics)
...

---

### Appendix A ??? Minimal Boot Example (Main)
```java
... Java code here ...
```

---

**This file is authoritative.** Any new code or refactor that touches hot paths must include corresponding OTel metrics/traces/logs as specified above. If in doubt, add the signal and document the rationale in the PR.


