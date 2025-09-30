# Changelog
All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]
### Added
- YAML-driven user event rule engine with hot-reload session-aware matching.
- HTTP persistence decorator that emits structured user events with metrics.
- `user-events` CLI dry-run command and sample HTTP exchanges.
- Documentation (`docs/user-events.md`) and example config (`config/user-events.example.yaml`).
- Terminal event domain model, emitter port, and logging adapter with unit coverage.
- TN3270 screen rule definitions with YAML loader, provider, engine, fixtures, and unit tests.
- CompositionRoot support for TN3270 screen rules via `TN3270_SCREENS_RULES` and terminal event publishing sink wiring.
- TN3270 screen event publishing sink that decorates the assembler, emits OpenTelemetry metrics, and forwards to existing Kafka sinks.
- Operator documentation (docs/tn3270-screen-events.md) detailing TN3270 screen event rule schema, defaults, and telemetry guidance.

### Changed
- Expanded config/tn3270-screens.example.yaml with inline guidance for screen rule authors.

## [1.0.0] - 2025-09-28
### Added
- Single YAML configuration flow shared across capture, live, assemble, and poster CLIs, with CLI overrides logged as WARN.
- Configurable protocol default filters (`protocolDefaultFilter.*`) so operators can supply non-standard TN3270 ports.

### Changed
- Test harnesses mute CLI and pipeline loggers during validation runs to keep `mvn verify` output signal-only.
- Documentation refreshed to reference YAML-driven launches, including ops, developer, telemetry, and architecture guides.


## [0.3.0] - 2025-09-28
### Added
- TN3270 screen capture and session monitoring with enriched metrics and event emission.
- Flow assembler enhancements for TN3270 screen mapping and event attribution.

### Changed
- Documentation suite refreshed and Javadoc coverage tightened across public APIs.

## [0.2.0] - 2025-09-26
### Added
- Optional pcap4j-based capture path for JNI/JNA performance comparisons.
- Live-processing test scenarios covering persistence failures, back-pressure, and executor shutdown edge cases.
- OpenTelemetry metrics adapter with structured logging bootstrap.

### Changed
- Persistence workers now use an `ExecutorService` with bounded queues for graceful shutdown and back-pressure metrics.
- Capture adapters reorganised under `infrastructure.capture.{live|file}` for clearer hexagonal boundaries.
- CLI and configuration parsing consolidated with expanded validation and Javadoc coverage.

### Removed
- Legacy and unused assemblers plus no-op adapters that were superseded by the new pipeline.

## [0.1.0] - 2025-09-25
### Added
- Initial baseline release delivering capture -> assemble -> sink pipelines for HTTP and TN3270 traffic.
- Offline capture replay with seeded HTTP/TN3270 pcaps and cross-platform (Windows wpcap) support.
- Poster pipelines, Kafka integration, and comprehensive unit tests across flow assemblers, persistence, and CLI layers.

### Changed
- Performance optimisations (buffer reuse, allocation reductions, throughput improvements) and security hardening of capture paths.
- Logging standardised on SLF4J with structured context; CLI help updated with refactored configuration layout.

### Fixed
- End-to-end HTTP pipeline, JNI loader issues on Windows, and assorted logging defects identified during integration testing.

### Removed
- Legacy adapters and deprecated wiring replaced by the new capture/assembler implementation.









