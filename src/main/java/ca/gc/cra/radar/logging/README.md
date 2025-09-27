# RADAR Logging Module

## Purpose
Provides utilities for configuring SLF4J/Logback logging prior to pipeline startup. Ensures structured, consistent log output with MDC context.

## Key Classes
- `LoggingConfigurator` — Enables DEBUG logging when `--verbose` is supplied and ensures Logback picks up the bundled configuration.
- `Logs` — Helper methods for truncating values before logging to prevent leaking large payloads.

## Guidelines
- Always log through SLF4J; no `System.out` or `System.err` calls in production code.
- Include MDC keys such as `pipeline`, `protocol`, and `flowId` to aid correlation.
- Keep log messages concise and actionable; error logs must include context and avoid stack traces for expected failures.

## Testing
- Logging utilities are covered by unit tests (`src/test/java/ca/gc/cra/radar/logging/**`). When adding new helpers, assert MDC handling and truncation behaviour.

## Extending
- Update `logback.xml` under `src/main/resources` if new appenders or patterns are required. Document changes in Ops Runbook.
- Provide helper methods for recurring logging patterns rather than duplicating MDC setup across modules.

