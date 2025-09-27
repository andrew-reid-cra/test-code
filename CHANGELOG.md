# Changelog
All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]
### Added
- OpenTelemetry metrics adapter (`MetricsPort` implementation) with OTLP configuration surfaced across all CLIs.
- Offline pcap/pcapng ingest that reuses the live segment pipeline, pcap4j-backed capture mode, and supporting regression tests.
- Operator/developer documentation suite (Ops Runbook, Developer Guide, Telemetry Guide, Upgrade Guide, refreshed README).

### Changed
- Persistence workers migrated to an `ExecutorService` with uncaught handlers, graceful shutdown, and bounded queue backpressure (no poison pills).
- Normalized package layout under `infrastructure.capture.{live|file}`, protocol adapters, and persistence sinks for clearer ports/adapters boundaries.
- Build tightened: Maven site enables Javadoc, Surefire, Jacoco, Checkstyle, and SpotBugs reports for every `mvn verify` run.

### Deprecated
- External adapters must now implement the core `FlowAssembler` contract; contextual overloads remain deprecated pending removal.

### Removed
- Legacy/unused assemblers and interfaces (for example, `LegacyHttpAssembler`, `NoOpFlowAssembler`, `ContextualFlowAssembler`).

### Fixed
- Logging configuration clarified to rely exclusively on SLF4J/Logback; eliminated `System.out` usage and ensured telemetry boot before logging.

## [0.1.0] - 2025-09-25
- Initial baseline release (capture -> assemble -> sink pipeline with HTTP and TN3270 support).

