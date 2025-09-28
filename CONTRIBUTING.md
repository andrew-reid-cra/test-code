# Contributing to RADAR

Thank you for investing in RADAR. The project powers mission-critical capture pipelines, so every change must preserve throughput, observability, and security.

## Branching and Commit Strategy
- Develop on short-lived feature branches named eature/<slug> or ix/<issue>; keep main releasable at all times.
- Rebase before opening a pull request to avoid merge commits in history.
- Use [Conventional Commits](https://www.conventionalcommits.org/) with a clear scope when it adds value (eat(capture): ...).
- Commit message template (adapt the body to your change):
  `
  docs: add/update complete RADAR documentation suite (ops, dev, telemetry, upgrade)

  - Refresh root README with quick start, CLI flags, architecture overview
  - Add/update OPS_RUNBOOK, DEVELOPER_GUIDE, TELEMETRY_GUIDE, UPGRADE_GUIDE under docs/
  - Introduce CONTRIBUTING with enterprise PR checklist
  - Update CHANGELOG with Unreleased entries
  - Add/refresh architecture diagrams (Mermaid) and package maps
  - Add/refresh per-module READMEs
  `

## Pull Request Checklist
Verify every item before requesting review:
- **Tests** ? JUnit 5 unit, integration, and performance tests added or updated. Critical logic stays at 100% coverage, overall coverage remains ?80%. Attach JaCoCo evidence when coverage moves.
- **Telemetry** ? Metrics, spans, and logs updated for new behaviour. Unit tests assert metric emission via in-memory adapters where feasible.
- **Performance** ? Audit allocations, buffer reuse, batching, and hot loops. No new blocking calls in hot paths; executor sizing documented.
- **Security** ? Validate all inputs, scrub secrets, honour OWASP and CERT Java guidelines. Never log payloads or credentials.
- **Documentation** ? Update README files (root + modules), architecture diagrams, guides, and inline Javadoc for every new public type or method.
- **Javadoc** ? Run mvn -DskipTests=true javadoc:javadoc to ensure public APIs are documented without warnings.
- **Changelog** ? Add an [Unreleased] entry in [CHANGELOG.md](CHANGELOG.md) describing what changed and why.
- **Observability** ? Confirm OpenTelemetry bootstrap succeeds (metricsExporter/env vars). Update dashboards or alert runbooks when metric names or semantics move.
- **Build** ? Run mvn -q -DskipTests=false verify locally and confirm quality gates (Checkstyle, SpotBugs, Jacoco, Surefire/Failsafe) pass.

## Coding Standards
- Java SE 17+ (future-safe up to Java 25); Maven for build orchestration and dependency management.
- Hexagonal architecture: domain/application stay framework-free; adapters implement ports for capture, protocols, sinks, telemetry.
- Immutability and thread safety by default. No global mutable state, reflection hacks, or deprecated APIs.
- SLF4J logging only; propagate MDC context (pipeline, lowId, sink, protocol). No System.out.
- Concurrency uses java.util.concurrent abstractions. Avoid blocking calls in hot loops; document executor sizing choices.
- Observability is mandatory: wrap hot paths with OpenTelemetry spans, emit metrics via MetricsPort, propagate trace context across executors.

## Running Quality Gates
| Goal | Command | Notes |
| --- | --- | --- |
| Full build + tests + reports | mvn -q -DskipTests=false verify | Runs unit/integration tests, Checkstyle, SpotBugs, Jacoco, Surefire, Failsafe, Site reports. |
| Javadoc | mvn -DskipTests=true javadoc:javadoc | Ensures all public APIs are documented; review warnings as failures. |
| Coverage report | 	arget/site/jacoco/index.html | Keep overall ?80% and critical logic at 100%; link in PR. |
| Benchmarks (optional) | mvn -Pbenchmarks test | Run before landing hot-path optimizations. |

## Getting Help
- Use draft PRs for early design feedback and to discuss architectural changes.
- Tag TODOs as // TODO(radar-<issue-id>): ... and file follow-up tickets before merging.
- Escalate security or privacy concerns privately to maintainers; do not include sensitive captures in public issues.

Deliver every contribution with production posture: more secure, more observable, more maintainable.
