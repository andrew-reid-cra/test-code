# Contributing to RADAR

Thank you for investing in RADAR. The project targets production capture pipelines, so every change must preserve throughput, observability, and security.

## Branching and Commit Strategy
- Base all work on short-lived feature branches named `feature/<unit>` or `fix/<issue>`. The `main` branch is protected and must remain releasable.
- Use [Conventional Commits](https://www.conventionalcommits.org/) for every commit. Include scope when it clarifies the affected module (for example, `feat(capture): add packet drop metric`).
- Preferred template (adapt as needed while keeping the summary under 72 characters):
  ```
  docs: add complete RADAR documentation suite (ops, dev, telemetry, upgrade)

  - Refresh root README with quick start, CLI flags, architecture overview
  - Add OPS_RUNBOOK, DEVELOPER_GUIDE, TELEMETRY_GUIDE, UPGRADE_GUIDE under docs/
  - Introduce CONTRIBUTING with enterprise PR checklist
  - Update CHANGELOG with Unreleased entries
  - Add/refresh architecture diagrams (Mermaid) and package maps
  - Add/refresh per-module READMEs
  ```
- Rebase before opening a pull request. Squash commits only when history would otherwise be noisy.

## Pull Request Checklist
Verify every item before requesting review:
- Tests: JUnit 5 unit and integration tests added or updated. Critical logic must maintain 100% coverage; overall coverage must stay at or above 80%. Attach evidence from JaCoCo if coverage moved.
- Telemetry: Metrics, traces, and logs updated for new behaviour. Unit tests assert metric emission using in-memory readers where feasible.
- Performance: Assess allocation counts, buffer reuse, and blocking calls. Confirm no new blocking operations were added to hot loops and that executor tuning remains safe.
- Security: Re-run input validation checks. Ensure no secrets, credentials, or packet payloads are logged. Follow OWASP and CERT Java guidance.
- Documentation: Update README(s), module guides, architecture docs, and inline Javadoc for all new public types or methods.
- Changelog: Add an entry under the `[Unreleased]` heading summarising what changed and why.
- Observability: Confirm OpenTelemetry exporters still bootstrap. Update dashboards or alert runbooks if metric names or semantics changed.
- Build: Run `mvn -q -DskipTests=false verify` locally and attach the build summary to the PR description. Do not merge with failing checks.

## Coding Standards
- Java SE 17+ with an eye toward future LTS releases (up to 25).
- SLF4J for logging; never use `System.out` or `System.err` for application logs.
- Prefer immutability, thread-safe designs, and defensive copies at module boundaries.
- Use `java.util.concurrent` utilities for parallelism; avoid raw `Thread` unless justified in documentation and code review.
- No global mutable state, reflection hacks, or deprecated APIs.
- Wrap hot paths and asynchronous boundaries with OpenTelemetry spans and ensure trace context propagates across executors.

## Running Quality Gates
- `mvn -q -DskipTests=false verify` — runs unit tests, integration tests, JaCoCo coverage, Checkstyle, and SpotBugs (if configured in the POM).
- `mvn -DskipTests=true javadoc:javadoc` — regenerates API docs; ensure no Javadoc regressions.
- `mvn -Pbenchmarks test` (if enabled) — run performance suites before committing hot-path changes.

## Getting Help
- Join the project chat or open a draft PR early for architectural feedback.
- Document outstanding work with TODO comments tagged `// TODO(radar-<issue>)` linked to a ticket.
- Escalate security or privacy concerns privately to project maintainers; do not post sensitive details on public issues.

