# RADAR Configuration Module

## Purpose
Centralises CLI configuration records (`CaptureConfig`, `AssembleConfig`, `PosterConfig`), enumeration helpers (`CaptureProtocol`, `IoMode`), and the `CompositionRoot` that wires ports to adapters.

## Responsibilities
- Validate CLI inputs (ranges, printable characters, host/port formats) before pipelines start.
- Provide sensible defaults (interface `eth0`, snaplen 65535, buffer 256 MiB, file rotation 512 MiB).
- Build and return fully wired use cases (capture, live, assemble, poster) with configured adapters and metrics port.

## Key Types
- `CaptureConfig` — Live/offline capture settings, persistence tuning knobs, Kafka routing.
- `PosterConfig` — Poster inputs/outputs, decode modes, Kafka integration.
- `AssembleConfig` — Input directories, protocol enablement, streaming limits.
- `CompositionRoot` — Instantiates adapters, buffer pools, executors, and metrics based on config.

## Testing
- Configuration parsing is covered by unit tests in `src/test/java/ca/gc/cra/radar/config/**` (range checks, CLI map parsing, validation errors).
- Ensure new config options include exhaustive tests, meaningful error messages, and documentation updates.

## Metrics
Configuration code does not emit metrics; it ensures `MetricsPort` implementations are provisioned and ready before use cases run.

## Extending
- Keep new options backwards compatible; add defaults to records and update parsing helpers.
- Document new knobs in README, Ops Runbook, and Upgrade Guide.
- Expose toggles via environment variables or CLI only after validating naming collisions.

