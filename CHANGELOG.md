# Changelog
All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]
### Added
- OpenTelemetry metrics adapter (MetricsPort implementation) with OTLP export enabled via environment variables or CLI flags.
- Comprehensive operator and developer documentation suite (Ops Runbook, Developer Guide, Telemetry Guide, Upgrade Guide, refreshed README).

### Changed
- Persistence workers migrated to an ExecutorService with uncaught handlers, graceful shutdown, and bounded queue backpressure (no poison pills).
- Normalized package layout under ca.gc.cra.radar.infrastructure.capture.{live|file|pcap} and consolidated protocol/persistence adapters to clarify hexagonal boundaries.
- Build tightened: mvn verify now runs Javadoc, Surefire, Failsafe, Jacoco, Checkstyle, and SpotBugs reports as part of the default pipeline.

### Removed
- Legacy and unused assemblers/interfaces (for example, LegacyHttpAssembler, NoOpFlowAssembler, ContextualFlowAssembler).

### Fixed
- Logging configuration relies exclusively on SLF4J/Logback; eliminated System.out usage and ensured telemetry boots before logging.

## [0.1.0] - 2025-09-25
- Initial baseline release (capture -> assemble -> sink pipeline with HTTP and TN3270 support).
