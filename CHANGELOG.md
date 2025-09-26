# Changelog
All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]
### Added
- feat(observability): replace noop metrics with OpenTelemetry adapter, CLI configuration, and unit tests.
- feat(capture): add offline PCAP/PCAPNG ingest that shares the live segment pipeline.
- feat(capture): apply BPF filtering and snaplen controls to file-backed packet sources.
- test(capture): cover offline capture with a bundled http_get.pcap fixture and compose smoke test.
- docs(capture): document offline capture usage and flags in README.
- docs(ops): new `docs/ops/operations.md` runbook covering CLI commands, exit codes, and tuning tips.
- docs(dev): contributed `docs/dev/development.md` with module layout, build steps, and release checklist.
- docs(upgrade): `docs/upgrade/UPGRADING.md` outlining pipeline migrations and dry-run workflow.
- build(site): Maven site/reporting configuration with Javadoc, Surefire, Jacoco, and Checkstyle outputs.

### Changed
- docs(javadoc): refreshed public API comments, added package overviews, and documented LegacyHttpAssembler deprecation details.
- build(jacoco): constrained coverage collection to project packages to avoid JDK instrumentation issues.

### Deprecated
- docs(upgrade): documented the pending removal of `LegacyHttpAssembler`; migrate to `PosterUseCase`-based pipelines.
