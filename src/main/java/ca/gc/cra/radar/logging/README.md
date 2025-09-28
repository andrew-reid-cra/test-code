# RADAR Logging Module

## Purpose
Provides utilities for configuring SLF4J/Logback logging before pipelines start. Ensures structured, consistent log output with MDC context and safe truncation helpers.

## Key Classes
- LoggingConfigurator ? Enables DEBUG logging when --verbose is supplied and bootstraps Logback using bundled configuration.
- Logs ? Helper methods for truncating values before logging to prevent leaking large payloads or secrets.

## Guidelines
- Always log through SLF4J; never use System.out or System.err in production code.
- Populate MDC keys such as pipeline, protocol, lowId, and sink to aid correlation across threads.
- Keep log messages concise and actionable; include context and avoid stack traces for expected failures.

## Testing
- Logging helpers are covered by tests under src/test/java/ca/gc/cra/radar/logging/**. When adding utilities, assert MDC handling and truncation behaviour.

## Extending
- Update logback.xml under src/main/resources if new appenders or patterns are required and document the change in the Ops Runbook.
- Provide helper methods for recurring logging patterns rather than duplicating MDC setup across modules.
