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

### 2.1 Capture Module (NIC → Segment Queue)
**Attributes (common):**  
`adapter={jnr-libpcap|pcap4j}`, `if_name`, `protocol={tcp|udp|other}`, `queue=segment`

**Counters**
- `radar.capture.packets` (1) — total packets captured  
- `radar.capture.bytes` (By) — total bytes captured  
- `radar.capture.dropped` (1) — kernel/driver drops (if available)  
- `radar.capture.errors` (1) — capture errors (timeouts, syscalls, adapter faults)

**Histograms**
- `radar.capture.batch.size` (1) — packets per poll/batch  
- `radar.capture.poll.latency` (ms) — time in poll/read calls

**Observable Gauges**
- `radar.capture.queue.depth` (1) — pending segments in queue  
- `radar.capture.throughput` (By/s) — rolling byte rate (calculate in code)

**Traces**
- **Span:** `capture.poll` around each poll/batch  
  - Attributes: `batch.size`, `adapter`, `if_name`  
  - Events on anomalies: `drop`, `timeout`, `buffer_overrun`

**Logs**
- Level **WARN/ERROR** for adapter failures with `adapter`, `if_name`, `errno`, `consecutive_failures`.

---

### 2.2 Assembler Module (Segment Queue → Flow/Message)
**Attributes (common):**  
`protocol={http|tn3270|other}`, `queue={segment|flow}`, `reassembly={inorder|reorder}`, `buffer_pool={on|off}`

**Counters**
- `radar.assembler.sessions.started` (1)  
- `radar.assembler.sessions.terminated` (1)  
- `radar.assembler.messages.reassembled` (1) — HTTP msgs or TN3270 transactions  
- `radar.assembler.reassembly.errors` (1) — checksum, gaps, timeouts  
- `radar.assembler.tcp.retransmissions.seen` (1) — if available

**Histograms**
- `radar.assembler.reassembly.latency` (ms) — segment→message latency  
- `radar.assembler.message.size` (By) — payload size distribution

**Observable Gauges**
- `radar.assembler.sessions.active` (1)  
- `radar.assembler.queue.depth` (1) — inflight segments/messages  
- `radar.assembler.buffer.pool.inuse` (By)

**Traces**
- **Span:** `assembler.reassemble` per message/flow  
  - Link to capture span via **span link** or **context propagation** (see §4).  
  - Events: `gap_detected`, `reorder`, `timeout_eviction`

**Logs**
- Structured **INFO** when switching protocol parsers; **WARN** for recoverable parse errors; **ERROR** for corrupt sequences dropped.

---

### 2.3 Sink Module (Message → Persistence/Outbound)
**Attributes (common):**  
`sink={opensearch|file|kinesis|http}`, `queue={message|persist}`

**Counters**
- `radar.sink.records.posted` (1)  
- `radar.sink.records.failed` (1)  
- `radar.sink.retries` (1)  

**Histograms**
- `radar.sink.latency` (ms) — request→ack time  
- `radar.sink.payload.size` (By)

**Observable Gauges**
- `radar.sink.queue.depth` (1)  
- `radar.sink.endpoint.rtt` (ms) — rolling estimate if feasible

**Traces**
- **Span:** `sink.persist` or `sink.post`  
  - Attributes: `sink.type`, `endpoint`, `http.status_code` (if HTTP)  
  - Events: `retry_scheduled`, `backoff`, `circuit_open` (if using CB)

**Logs**
- **WARN** for transient failures with retry metadata; **ERROR** with correlation IDs on terminal failures.

---

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
- Drive synthetic traffic through **Capture → Assembler → Sink** and validate:
...

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

### Appendix A — Minimal Boot Example (Main)
```java
... Java code here ...
```

---

**This file is authoritative.** Any new code or refactor that touches hot paths must include corresponding OTel metrics/traces/logs as specified above. If in doubt, add the signal and document the rationale in the PR.
