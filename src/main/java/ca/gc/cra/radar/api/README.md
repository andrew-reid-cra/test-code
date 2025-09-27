# RADAR API / CLI Layer

## Purpose
Provides the command-line interface (`ca.gc.cra.radar.api.Main`) that dispatches to capture, live, assemble, poster, and utility subcommands. Handles argument parsing, telemetry bootstrap, and logging configuration before delegating to application use cases.

## Subcommands
- `capture` — Offline or live capture to segments (`CaptureCli`).
- `capture-pcap4j` — Alternate capture path using pcap4j for benchmarking (`CapturePcap4jCli`).
- `live` — Full capture + assemble + persist pipeline (`LiveCli`).
- `assemble` — Offline reassembly of stored segments (`AssembleCli`).
- `poster` — Render assembled conversations (`PosterCli`).
- `segbingrep` — Search `.segbin` payloads for byte sequences (`tools.SegbinGrepCli`).

## Responsibilities
- Parse `key=value` CLI arguments and flags (`CliArgsParser`, `CliInput`).
- Validate inputs and print helpful usage messages.
- Configure OpenTelemetry exporters via `TelemetryConfigurator`.
- Enable DEBUG logging with `--verbose` via `LoggingConfigurator`.
- Exit with explicit codes (`ExitCode`).

## Metrics
The CLI layer configures exporters but does not emit metrics directly; downstream use cases use `MetricsPort`. When adding new subcommands, ensure telemetry configuration remains consistent.

## Testing
- CLI behaviour is covered by tests under `src/test/java/ca/gc/cra/radar/api/**` (argument parsing, error handling, dry run flows).
- Tests should assert exit codes, validate summary usage output, and confirm telemetry properties are set when metrics options are supplied.

## Extending
- Add new subcommands by updating `Main` and providing a dedicated `*Cli` class.
- Keep CLI usage strings concise and document new options in `README.md` and `docs/OPS_RUNBOOK.md`.
- Maintain Javadoc for all public methods to satisfy documentation quality gates.

