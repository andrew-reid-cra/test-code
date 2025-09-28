# RADAR Configuration Module

## Purpose
Centralises CLI configuration records (CaptureConfig, LiveConfig, AssembleConfig, PosterConfig), enumerations (CaptureProtocol, IoMode), and the CompositionRoot that assembles ports and adapters into runnable pipelines.

## Responsibilities
- Validate CLI inputs (numeric ranges, printable characters, host/port combinations) before pipelines start.
- Provide sensible defaults (interface eth0, snaplen 65535, buffer 256 MiB, file rotation 512 MiB).
- Build and return wired use cases (capture, live, assemble, poster) with configured adapters, queues, and metrics ports.

## Key Types
- CaptureConfig ? Live/offline capture settings, persistence tuning knobs, Kafka routing.
- LiveConfig ? Persistence worker sizing, queue depths, protocol enablement for live mode.
- PosterConfig ? Poster inputs/outputs, decode modes, Kafka integration.
- AssembleConfig ? Input directories, protocol toggles, streaming thresholds.
- CompositionRoot ? Factory responsible for instantiating adapters, buffer pools, executors, and telemetry wiring.

## Testing
- Configuration parsing tests live in src/test/java/ca/gc/cra/radar/config/** and cover success and failure cases.
- New options must include validation tests, meaningful error messages, and documentation updates (README, Runbook, Upgrade Guide).

## Metrics
Configuration classes do not emit metrics directly; they ensure MetricsPort implementations are initialised before pipelines run.

## Extending
- Keep new options backwards compatible with defaults to avoid breaking scripts.
- Document every new knob in the root README, Ops Runbook, and Upgrade Guide.
- Expose toggles via CLI and environment variables only after validating clashes with existing names.
