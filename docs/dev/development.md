# RADAR Developer Guide

This guide describes how the RADAR code base is structured, how to run the build, and what is
required to ship changes safely.

## Architecture overview

RADAR is organised into ports-and-adapters modules:

- `ca.gc.cra.radar.api` ? CLI entry points and argument parsing.
- `ca.gc.cra.radar.application` ? use cases (`SegmentCaptureUseCase`, `AssembleUseCase`,
  `PosterUseCase`) and orchestration utilities.
- `ca.gc.cra.radar.config` ? immutable configuration records consumed by the CLIs.
- `ca.gc.cra.radar.domain` ? protocol-agnostic models (segments, flows, protocol IDs, bytes).
- `ca.gc.cra.radar.infrastructure` ? adapters for capture (libpcap), protocol detectors,
  persistence (filesystem/Kafka), poster rendering, and time/metrics ports.
- `ca.gc.cra.radar.adapter.kafka` ? Kafka-specific reader/writer adapters for the pipelines.

Data flows follow capture ? assemble ? poster. Capture emits `SegmentRecord`s, assemble converts
segments into protocol `MessagePair`s, poster renders human-readable reports or publishes them to
Kafka.

## Build, tests, and coverage

```
mvn -q clean verify
```
Runs unit tests, Checkstyle (Javadoc coverage), and Jacoco. Coverage data is written to
`target/site/jacoco/index.html`. Generate the full documentation site via:

```
mvn -q site
```
Site output is under `target/site/` and includes Javadoc, test reports
(`target/site/surefire-report.html`), Checkstyle results, and coverage reports.

## Profiling and performance harness

A lightweight harness lives in `tmp/`:

1. Capture a short sample (`radar capture ... out=tmp/capture`).
2. Assemble into pairs (`radar assemble in=tmp/capture out=tmp/pairs`).
3. Poster-run with `decode` toggles while attaching profilers:
   ```
   JAVA_TOOL_OPTIONS="-XX:+StartFlightRecording=filename=poster.jfr"    radar poster httpIn=tmp/pairs/http httpOut=tmp/poster/http decode=all
   ```
4. Analyse the resulting `poster.jfr` or use `async-profiler`/`perf` as needed.

When introducing performance-sensitive changes, commit the profiler command lines you used (for
reproducibility) under `docs/dev/perf-notes.md` if extended experiments are required.

## Coding standards

- Prefer `Objects.requireNonNull` and descriptive `IllegalArgumentException` messages for validation.
- Keep logging/diagnostics on `System.err` until SLF4J wiring lands; prefix messages with the CLI name
  (`capture:`, `assemble:`) as seen in existing code.
- Use `Optional` for optional CLI flags (see `PosterConfig`) and favour immutable records/collections.
- Thread-safety expectations should be documented via Javadoc (`@implNote` in use cases).

## Adding a protocol reconstructor

1. Create a new implementation of `ca.gc.cra.radar.application.port.ProtocolModule` that wires the
   protocol-specific `MessageReconstructor`, `PairingEngine`, and optional poster pipeline.
2. Provide an infrastructure adapter under `ca.gc.cra.radar.infrastructure.protocol.<protocol>`:
   - Flow assembler adapter (`FlowAssembler` impl) to translate TCP payloads.
   - Message reconstructor to emit domain `MessageEvent`s.
   - Poster pipeline (mirroring `HttpPosterPipeline` or `Tn3270PosterPipeline`).
3. Register the module in `CompositionRoot#protocolModules()` and provide configuration hooks in the
   relevant config record.
4. Add unit tests mirroring the existing HTTP/TN3270 suites (assembler, reconstructor, poster).
5. Document the new protocol in `docs/ops/operations.md` and update `UPGRADING.md` when introducing
   new flags or breaking changes.

## Release checklist

1. Update `CHANGELOG.md` (`[Unreleased]` section) and `docs/upgrade/UPGRADING.md` with any breaking
   or operator-facing changes.
2. Run `mvn -q clean verify` and `mvn -q site`; inspect `target/site/index.html` for missing reports.
3. Smoke-test the three CLIs using sample data (`docs/ops/operations.md` quick start commands).
4. Tag using semantic versioning (e.g., `v0.1.0`) and push the site artefacts if publishing to GitHub
   Pages (see `.github/workflows/site.yml` once enabled).
5. Squash or rebase per repository policy, ensuring commits follow Conventional Commit prefixes
   (`docs`, `build`, `fix`, `feat`, etc.).
