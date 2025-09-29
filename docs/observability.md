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
  - Examples: `radar.capture.packets.total` (1), `live.persist.latencyNanos` (ns), `tn3270.parse.latency.ms` (ms)

### 1.4 Performance Principles
- Prefer **asynchronous** (observable) gauges for queue depth, pool utilization.  
- Use **Histograms** for latency/size distributions.  
- Add **Attributes** sparingly; bound cardinality (e.g., protocol family, adapter).  
- Enable **exemplars** to link metrics to traces where supported.

---

## 2) Module Telemetry Specifications

### 2.1 Capture Module (NIC -> Segment Queue)
**Attributes (common):**  
`impl={pcap4j|jnr-libpcap}`, `source=<iface-or-path>`, `offline=<true|false>`, `status={ok|null.data|poll.error|poll.failure}`

**Counters**
- `radar.capture.packets.total` (1) -> incremented for every packet delivered by the adapter telemetry helper.
- `radar.capture.bytes.total` (By) -> total bytes returned from `Packet#getRawData()`.
- `radar.capture.errors.total` (1) -> non-success polls, including null payloads and exceptions (status attribute differentiates cases).
- `capture.segment.persisted` (1) -> `SegmentCaptureUseCase` persisted a TCP segment after decoding.
- `capture.segment.skipped.decode` / `capture.segment.skipped.pureAck` (1) -> decoder rejected the frame or the segment was a pure ACK filtered out.

**Histograms**
- `radar.capture.poll.latency` (ns) -> duration of each `handle.getNextPacket()` call. Captured at the adapter level so we can contrast live vs offline sources.

**Observable Gauges**
- Not yet implemented. Use downstream `live.persist.queue.*` metrics to understand queue pressure until capture-side gauges land.

**Traces**
- `capture.start` spans wrap adapter bootstrap and record `impl`, `offline`, `snaplen`, and `filter.present`.
- `capture.poll` spans wrap each poll, attach `bytes` and `timestamp.micros`, and record exceptions when the JNI/pcap layer raises.

**Logs**
- WARN/ERROR statements surface adapter failures with contextual fields (`iface`/`pcapFile`, errno). `SegmentCaptureUseCase` enriches logs with `flowId` via MDC once segments are decoded.

---


### 2.2 Assembler Module (Segment Queue -> Flow/Message)
**Attributes (common):**  
`radar.metric.key` for every measurement, plus `protocol=tn3270` where noted.

**Counters**
- `<prefix>.segment.accepted` -> `FlowProcessingEngine` accepted an oriented TCP segment (`prefix` is `live` or `assemble`).
- `<prefix>.protocol.detected` / `.protocol.disabled` / `.protocol.unsupported` -> outcomes from protocol classification before building reconstructor/pairing engines.
- `<prefix>.flowAssembler.error` -> exceptions raised by the flow assembler implementation.
- `<prefix>.pairs.generated` -> message pairs emitted from `FlowProcessingEngine` back to the pipeline.
- `live.segment.skipped.decode` -> live decoder dropped a frame before it reached flow processing.
- `assemble.pairs.persisted` -> offline assembler persisted a message pair to the configured sink.
- `http.flowAssembler.payload` / `tn3270.flowAssembler.payload` / `<prefix>.payload` -> protocol adapters accepted payload-bearing segments.

**Histograms**
- `<prefix>.byteStream.bytes` -> payload bytes emitted per flush from `FlowProcessingEngine`.
- `protocol.http.bytes` / `protocol.tn3270.bytes` -> recorded by protocol metrics helpers as reconstructed payload volume.
- `tn3270.parse.latency.ms` -> parser latency for host writes and client submits inside `Tn3270AssemblerAdapter`.

**Observable Gauges**
- `tn3270.sessions.active` (UpDownCounter) -> counts active TN3270 sessions; decremented on close.

**Traces**
- `tn3270.parse.host_write` and `tn3270.parse.client_submit` spans wrap parser work, set session/screen attributes, and propagate context with `Scope` so downstream logs correlate.
- Capture spans propagate via MDC (`flowId`) and executor hand-offs inside `LiveProcessingUseCase`.

**Logs**
- Flow and assembler stages set MDC keys (`pipeline`, `flowId`, `protocol`) before logging successes or failures.
- Recoverable parser errors log the session key and increment counters, while unrecoverable ones bubble up to `live.persist.error` or shutdown paths for visibility.

---


### 2.3 Sink Module (Message -> Persistence/Outbound)
**Attributes (common):**  
`radar.metric.key` (always). Persistence workers currently emit no extra dimensions; rely on pipeline-specific MDC (`pipeline`, `protocol`) for log correlation.

**Counters**
- `live.pairs.persisted` -> successfully batched pairs written by the live persistence executor.
- `live.persist.enqueued` / `.enqueue.retry` / `.enqueue.dropped` / `.enqueue.interrupted` -> enqueue outcomes for the bounded persistence queue.
- `live.persist.error` / `.worker.uncaught` / `.worker.interrupted` -> worker life-cycle issues surfaced by `LiveProcessingUseCase`.
- `live.persist.shutdown.force` / `.shutdown.interrupted` / `.flush.error` -> shutdown anomalies requiring manual intervention.
- `assemble.pairs.persisted` -> offline assembler flushed a message pair to disk.

**Histograms**
- `live.persist.latencyNanos` / `.latencyEmaNanos` -> persistence round-trip durations (raw and EMA-smoothed).
- `live.persist.enqueue.waitNanos` / `.waitEmaNanos` -> time spent waiting for queue capacity.
- `live.persist.queue.depth` / `.queue.highWater` -> instantaneous and max queue depth samples recorded during runtime.

**Observable Gauges**
- `live.persist.worker.active` -> observed worker counts sampled pre/post shutdown; exported via the histogram instrument backing `MetricsPort.observe`.

**Traces**
- Dedicated sink spans are not yet implemented. Until then, correlate persistence behaviour through the metrics above plus MDC-tagged WARN/ERROR logs.

**Logs**
- Saturation warnings (queue full, retries) include depth, worker counts, and retry totals.
- Persistence adapters log flush/close errors with the pipeline MDC so alerts can point directly at failing sinks.

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
- Metrics share the radar.metric.key attribute so dashboards can differentiate live persistence from offline assemble sinks.
- Saturation events also emit WARN logs summarizing queue depth and retry counts.


## 3) Java Implementation Patterns

### 3.1 Bootstrapping the OTel SDK (OTLP)
`ca.gc.cra.radar.infrastructure.metrics.OpenTelemetryBootstrap` wires the SDK using environment/JVM properties:

```java
BootstrapConfig config = BootstrapConfig.fromEnvironment();
if (config.exporter() == ExporterMode.NONE) {
  log.info("OpenTelemetry metrics exporter disabled (exporter=none)");
  return BootstrapResult.noop();
}
SdkMeterProviderBuilder builder = SdkMeterProvider.builder()
    .setResource(config.resource());
MetricReader reader = createReader(config);
if (reader != null) {
  builder.registerMetricReader(reader);
}
SdkMeterProvider provider = builder.build();
Meter meter = provider.meterBuilder("ca.gc.cra.radar")
    .setInstrumentationVersion(config.instrumentationVersion())
    .build();
```

- `OTEL_METRICS_EXPORTER` / `otel.metrics.exporter` toggles OTLP vs noop (`none` skips all emission but keeps the app running).
- `OTEL_EXPORTER_OTLP_ENDPOINT` defaults to `http://localhost:4317`; override via CLI flags or JVM props.
- `OTEL_RESOURCE_ATTRIBUTES` appends to the detected resource (`service.namespace=ca.gc.cra`, `service.name=radar`, `service.version` from Maven, `service.instance.id` from hostname/MXBean).
- `BootstrapResult` owns the provider/meter and exposes `isNoop()` for callers; tests access `forTesting(reader)` to bind in-memory exporters without touching the real environment.

### 3.2 Defining Instruments (Capture Example)
`Pcap4jPacketSource.Telemetry` centralises capture metrics and attributes:

```java
Meter meter = GlobalOpenTelemetry.getMeter("ca.gc.cra.radar.capture.pcap4j");
Attributes attributes = Attributes.builder()
    .put(ATTR_IMPL, "pcap4j")
    .put(ATTR_SOURCE, source)
    .put(ATTR_OFFLINE, offline)
    .build();
LongCounter packets = meter.counterBuilder("radar.capture.packets.total")
    .setUnit("1")
    .setDescription("Packets captured by the pcap4j adapter")
    .build();
LongCounter bytes = meter.counterBuilder("radar.capture.bytes.total")
    .setUnit("By")
    .setDescription("Bytes captured by the pcap4j adapter")
    .build();
LongHistogram latency = meter.histogramBuilder("radar.capture.poll.latency")
    .ofLongs()
    .setUnit("ns")
    .setDescription("Poll latency for pcap4j packet source")
    .build();
```

- `telemetry.packetCounter.add(1, telemetry.baseAttributes)` and friends ensure every poll emits consistent dimensions.
- `telemetry.withStatus("poll.failure")` enriches error counters with a `status` attribute so dashboards can break down failure modes.
- Counters/histograms survive adapter reuse because instruments are cached on the `Telemetry` record.

### 3.3 Tracing with Context Across Queues
The TN3270 assembler keeps spans and metrics aligned even as work hops across executors:

```java
Span span = tracer.spanBuilder("tn3270.parse.host_write").startSpan();
try (Scope ignored = span.makeCurrent()) {
  bytesCounter.add(processedBytes, telemetryAttributes);
  ScreenSnapshot snapshot = Tn3270Parser.parseHostWrite(filtered, context.state);
  parseLatency.record(durationMs, telemetryAttributes);
  span.setAttribute("tn3270.session", context.key.canonical());
  span.setAttribute("tn3270.screen.hash", screenHash == null ? "" : screenHash);
} catch (Exception ex) {
  span.recordException(ex);
  span.setStatus(StatusCode.ERROR, ex.getMessage() == null ? "host parse error" : ex.getMessage());
  throw ex;
} finally {
  span.end();
}
```

- `try (Scope ignored = span.makeCurrent())` ensures downstream logs inherit the trace identifiers, even when persistence happens on worker threads.
- Client submissions reuse the same pattern (`tn3270.parse.client_submit`) with sanitised AID/input fields and redaction policies before logging.
- Live pipelines complement spans with MDC keys (`pipeline`, `flowId`, `protocol`) so metrics, traces, and logs converge on the same identifiers.

### 3.4 Structured Logs via SLF4J with Trace IDs
- Use a logging layout that includes `trace_id` and `span_id` (via MDC or OTel autoinstrumentation bridge).  
- Log **key/value** pairs (JSON if supported) to correlate with metrics/traces.  
- Do **not** log packet contents or PII unless redacted and approved.

---

## 4) Testing Telemetry

### 4.1 Unit Tests (SDK In-Memory)
- `OpenTelemetryMetricsAdapterCounterTest` drives `increment()` three times, flushes via `InMemoryMetricReader`, and asserts the exported `LongSum` plus the `radar.metric.key` attribute and resource defaults (`service.name=radar`, `service.namespace=ca.gc.cra`).
- `OpenTelemetryMetricsAdapterHistogramTest` exercises `observe()` and confirms histogram aggregation while verifying that sanitised instrument names still carry the original metric key.
- `OpenTelemetryBootstrapNoopTest` sets `otel.metrics.exporter=none` to guarantee we fall back to the noop provider without throwing, protecting CLI startup when telemetry is disabled.

### 4.2 Integration/Perf Tests
- `LiveProcessingUseCasePersistenceTest` uses the `RecordingMetrics` test double to assert queue depth, latency, retry, and worker metrics fire under load, back-pressure, and failure scenarios.
- `LiveProcessingUseCaseConcurrencyTest` and `LiveProcessingUseCaseTest` stress multi-threaded execution, ensuring saturation paths increment `live.persist.*` counters and that shutdown metrics surface as expected.
- `EndToEndPcapIT` replays bundled PCAP fixtures through capture ? assemble ? poster CLIs; run it with OTLP enabled to watch `capture.segment.persisted`, `assemble.pairs.persisted`, and downstream metrics flow end-to-end.
- Performance profile runs (`mvn -Pperf verify`) can emit metrics while benchmarks execute; set OTEL environment variables before invoking the profile so histograms capture regression data.

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
- Copy `config/radar-example.yaml` to a working file (for example `./radar-telemetry.yaml`) and adjust metrics settings before launching the CLI.
- **Invoke RADAR with metrics enabled (choose the CLI under test):**
  ```bash
  java -cp target/RADAR-1.0.0.jar \
    ca.gc.cra.radar.api.Main capture --config=./radar-telemetry.yaml \
    iface=en0 \
    metricsExporter=otlp \
    otelEndpoint=http://localhost:4317 \
    otelResourceAttributes=service.name=radar-capture,deployment.environment=dev
  ```
  - Alternate: export `OTEL_METRICS_EXPORTER`, `OTEL_EXPORTER_OTLP_ENDPOINT`, and `OTEL_RESOURCE_ATTRIBUTES` before invoking the CLI so every module shares the same config.
- **Trigger activity:** replay a PCAP or run a short capture/assemble/poster cycle so counters and histograms emit at least once.
- **Verify output:** the collector terminal should display metric points such as `capture.segment.persisted` or `live.persist.latencyNanos` with the `radar.metric.key` attribute and the expected resource attributes (`service.name`, `service.namespace`, etc.).
- **Promote the setup:** once the smoke test passes, repoint `otelEndpoint` (or the `OTEL_EXPORTER_OTLP_ENDPOINT` env var) to your staging collector and repeat the validation.




---

## 5) Alerting & SLO Hints
- **Capture Health:** Alert if `capture.segment.skipped.decode + capture.segment.skipped.pureAck` exceeds 5% of `capture.segment.persisted` over 10 minutes. Track `radar.capture.errors.total` by `status` to identify flaky interfaces.
- **Assembler Stability:** Watch `<prefix>.protocol.disabled` / `.protocol.unsupported` spikes; unexpected increases often mean configuration drift. Page when `tn3270.parse.latency.ms` p95 > 75 ms for 5 minutes.
- **Persistence SLO:** Keep `live.persist.latencyNanos` p95 under 5 ms and ensure `live.persist.enqueue.dropped` stays at zero; a single drop should trigger investigation.
- **Worker Reliability:** Create single-value panels for `live.persist.worker.uncaught`, `.worker.interrupted`, `.shutdown.force`, and `.flush.error`. Any non-zero value is a Sev2.
- **Throughput Trend:** Plot `capture.segment.persisted` and `live.pairs.persisted` side-by-side; divergence indicates assembly or sink bottlenecks.

---

## 6) Maven Coordinates (example)
| Artifact | Version | Purpose |
| --- | --- | --- |
| `io.opentelemetry:opentelemetry-api` | 1.36.0 | Runtime API used across adapters (`GlobalOpenTelemetry`, meters, tracers). |
| `io.opentelemetry:opentelemetry-sdk` | 1.36.0 | SDK implementation used by `OpenTelemetryBootstrap`. |
| `io.opentelemetry:opentelemetry-exporter-otlp` | 1.36.0 | OTLP gRPC exporter wired into the periodic reader. |
| `io.opentelemetry:opentelemetry-sdk-testing` | 1.36.0 | In-memory metric/span exporters for unit tests. |
| `org.slf4j:slf4j-api` + `ch.qos.logback:logback-classic` | 2.0.13 / 1.4.14 | Structured logging with MDC support and trace/span correlation. |

These are already declared in `pom.xml`; downstream modules embedding RADAR components should align with the same versions to avoid classpath conflicts.

---

## 7) Developer Checklist (Observability)
- Name new metrics with the `radar.<module>.<noun>[.<verb>]` pattern and update this guide plus dashboards in the same PR.
- Cover every new counter/gauge with a test (`InMemoryMetricReader` for adapters, `RecordingMetrics` for pipelines) so regressions are caught at build time.
- Ensure MDC keys you set (`pipeline`, `flowId`, `protocol`) are cleared in `finally` blocks; missing clean-up leaks context across threads.
- When introducing spans, add attributes sparingly and document them here; keep cardinality bounded.
- Run one CLI with `OTEL_METRICS_EXPORTER=otlp` before merging to confirm metrics flow end-to-end with your changes.

---

## 8) Dashboard Seeds (quick start)
- **Capture Throughput:** Plot `radar.capture.packets.total` (rate over 5m) grouped by `impl`/`source` attributes emitted by the capture adapter. Add a ratio panel of `capture.segment.skipped.decode` vs `capture.segment.persisted`.
- **Assembler Mix:** Stack `live.pairs.generated` and `assemble.pairs.generated` using the `radar_metric_key` attribute to highlight protocol volume trends; pair with `protocol.http.bytes` / `protocol.tn3270.bytes` for payload depth.
- **Persistence Latency:** Heatmap `live.persist.latencyNanos` quantiles with a single-value panel driven by `live.persist.latencyEmaNanos` to surface rolling averages.
- **TN3270 Health:** Table `tn3270.sessions.active` (current) alongside rates for `tn3270.events.submit.count` and `tn3270.events.render.count` to catch sudden session churn.

When using Prometheus/OTel Collector exporters, always group or filter by `radar_metric_key` to target the logical metric name regardless of sanitised instrument IDs.

---

## 9) Security & Privacy
- Avoid embedding payload contents or PII in metrics/logs. `Tn3270AssemblerAdapter` honours a redaction function for user inputs; extend it instead of logging raw fields.
- Trace/span attributes should remain low-cardinality; never include account numbers, SINs, or session secrets.
- `SegmentCaptureUseCase` and `AssembleUseCase` use MDC keys to reference flow identifiers; keep these high-level (IP:port) and scrub when threads exit.
- Only expose environment metadata through `OTEL_RESOURCE_ATTRIBUTES` (e.g., `deployment.environment`, `region`). Do not pass credentials or tokens via resource attributes.

---

## 10) Migration Notes (if you already have logs/metrics)
- Pre-1.0 builds used ad-hoc counters; replace them with `OpenTelemetryMetricsAdapter` and update dashboards to group by `radar_metric_key`.
- If you previously relied on `NoOpMetricsAdapter`, keep the same wiring--`OpenTelemetryBootstrap` respects `OTEL_METRICS_EXPORTER=none` so disabling telemetry still works.
- Prometheus/Grafana dashboards should pivot to the new metric names (`live.persist.*`, `capture.segment.*`, `tn3270.parse.*`). Sanity-check alert rules after renaming.
- Tests that stubbed metrics should switch to `RecordingMetrics` or `InMemoryMetricReader` utilities now that the SDK is part of the toolchain.

---

### Appendix A: Minimal Boot Example (Main)
```java
import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
import ca.gc.cra.radar.infrastructure.capture.Pcap4jPacketSource;
import ca.gc.cra.radar.infrastructure.metrics.OpenTelemetryMetricsAdapter;
import ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap;

public final class MinimalTelemetryDemo {
  private MinimalTelemetryDemo() {}

  public static void main(String[] args) throws Exception {
    OpenTelemetryMetricsAdapter metrics = new OpenTelemetryMetricsAdapter();
    Pcap4jPacketSource packetSource = new Pcap4jPacketSource(
        "en0", 65535, true, 10, 4 * 1024 * 1024, true, null);
    SegmentPersistencePort persistence = /* your persistence adapter */ null;

    SegmentCaptureUseCase capture =
        new SegmentCaptureUseCase(packetSource, new FrameDecoderLibpcap(), persistence, metrics);
    capture.run(); // metrics + spans emit automatically via MetricsPort hooks
  }
}
```

The CLI wiring (see `CapturePcap4jCli` and `CompositionRoot`) follows the same pattern: instantiate the adapter once, pass it through dependency construction, and let the periodic reader ship metrics to the configured collector.

---

**This file is authoritative.** Any new code or refactor that touches hot paths must include corresponding OTel metrics/traces/logs as specified above. If in doubt, add the signal and document the rationale in the PR.






