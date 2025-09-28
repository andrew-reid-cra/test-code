# TN3270 Module

## Overview
The TN3270 module converts reconstructed TN3270 Telnet flows into structured user-action events. It maintains per-session virtual screens, decodes 3270 orders, and extracts modified unprotected field values so that host interactions can be analyzed or replayed downstream. The module honours RADAR''s hexagonal architecture: domain parsing lives under `ca.gc.cra.radar.domain.protocol.tn3270`, application ports expose assembler and sink behavior, and infrastructure adapters provide Telnet filtering, assembler orchestration, and Kafka egress.

## Developer Guide
- **Assemblers:** `Tn3270AssemblerAdapter` receives `MessagePair` instances, filters Telnet negotiations, updates `Tn3270SessionState`, and emits `Tn3270Event` instances through the configured `Tn3270EventSink`.
- **Domain parsing:** `Tn3270Parser` interprets host writes (Erase/Write, Write, Write Structured Field) plus standard orders (SBA, SF, IC, EUA). Client submits decode AID keys and capture modified fields. Labels are inferred from protected fields directly left of the input field.
- **Session state:** `Tn3270SessionState` tracks the 24×80 buffer by default, field metadata, and last computed screen hash. Field metadata keeps attribute flags, lengths, derived labels, and MDT markers.
- **Redaction:** Redaction policies are supplied as regex patterns via configuration. The assembler applies the policy before handing events to sinks.
- **Kafka sink:** `Tn3270KafkaPoster` serialises events to `radar.tn3270.user-actions.v1` and optional `radar.tn3270.screen-renders.v1` topics. Events include schema version, ULID identifiers, session metadata, and pipeline provenance.
- **TODOs:** Structured Field subtypes, DBCS handling, and precise MDT behaviour beyond simple modified field extraction remain open for future iterations.

## Telemetry
- **Spans:**
  - `tn3270.parse.host_write` wraps host render parsing and annotates session and screen hash.
  - `tn3270.parse.client_submit` wraps client submit parsing and records AID values.
- **Metrics:**
  - `tn3270.events.render.count` – counter of screen render events emitted.
  - `tn3270.events.submit.count` – counter of user submit events emitted.
  - `tn3270.bytes.processed` – counter of filtered TN3270 bytes processed.
  - `tn3270.parse.latency.ms` – histogram of parser latency per record.
  - `tn3270.sessions.active` – up/down counter of active tracked sessions.

## Configuration
- `tn3270.emitScreenRenders` (boolean, default `false`): enable emission of `SCREEN_RENDER` events.
- `tn3270.screenRenderSampleRate` (double, `0.0`–`1.0`, default `0.0`): fraction of renders to emit when screen renders are enabled.
- `tn3270.redaction.policy` (regex, default `(?i)^(SIN)$`): case-insensitive pattern used to redact sensitive field values before emission.

These properties are recognised in both capture (`CaptureConfig`) and assemble (`AssembleConfig`) pipelines. Default values live under `src/main/resources/config/default-capture.properties` and can be overridden via CLI or external configuration files.

## Roadmap
1. **Structured Field Support**
   - Add parsers for Write Structured Field (WSF) responses, including Read Buffer, Read Partition, and Query Reply handling.
   - Extend `Tn3270SessionState` to manage multiple partitions and attribute bytes, preserving compatibility with existing events.
   - Implement unit tests with captured WSF fixtures covering erase/write/partition flows.
2. **Modified Data Tag (MDT) Fidelity**
   - Track MDT bits on a per-field basis and emit only host-acknowledged modifications.
   - Update `parseClientSubmit` to reconstruct Read Modified/Read Modified All responses accurately, including null-field submissions.
   - Add regression tests verifying MDT resets after host acknowledgement.
3. **Double-Byte Character Set (DBCS) Handling**
   - Enhance field decoding to support DBCS orders (SFE, SF with DBCS), including buffer expansion to handle shift-out/shift-in sequences.
   - Integrate charset detection (e.g., Cp939) when DBCS is present and document fallback/redaction strategies.
   - Benchmark decoding performance with synthetic DBCS workloads to keep GC impact predictable.
4. **Observability & Tooling**
   - Add dedicated metrics for structured-field coverage (e.g., `tn3270.sf.readBuffer.count`) and MDT miss rates.
   - Provide CLI toggles or config flags to enable verbose parser logging for targeted sessions.
5. **Documentation & Operations**
   - Publish operator procedures for detecting unsupported structured fields via metrics/logs.
   - Update runbooks with remediation steps and surface configuration defaults for MDT/DBCS handling.

Each milestone can be developed and merged independently; align with capture replay fixtures to validate before shipping.

