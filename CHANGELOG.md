# Changelog
All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]
### Added
- feat(capture): add pcap4j-backed capture CLI (`capture-pcap4j`) and adapter for performance comparisons.
- test(pipeline): cover persistence failure, back-pressure, and executor shutdown scenarios in live processing tests.
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
- refactor(pipeline): replace manual persistence workers with an ExecutorService, uncaught handler, and bounded shutdown logic.
- docs(pipeline): document persistence thread pool tuning in README and package overview; live CLI lists persistWorkers/persistQueueCapacity flags.
- docs(javadoc): refreshed public API comments, added package overviews, and pruned references to retired assembler shims.
- build(jacoco): constrained coverage collection to project packages to avoid JDK instrumentation issues.

### Deprecated
- docs(upgrade): external adapters must implement the core `FlowAssembler` contract; contextual overloads are deprecated in favour of domain-driven context propagation.

### Removed
- refactor(cleanup): removed `LegacyHttpAssembler`, `ContextualFlowAssembler`, and `NoOpFlowAssembler`; migrate to `PosterUseCase` + `HttpFlowAssemblerAdapter` pipelines or dedicated `FlowAssembler` test doubles.

