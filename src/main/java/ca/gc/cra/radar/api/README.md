# RADAR API / CLI Layer

## Purpose
Exposes command-line entry points (ca.gc.cra.radar.api.Main) that dispatch to capture, live, assemble, poster, and utility subcommands. Handles argument parsing, telemetry bootstrap, and logging configuration before delegating to application use cases.

## Subcommands
- capture ? Offline or live capture to segments (CaptureCli).
- capture-pcap4j ? Alternate capture path using pcap4j for benchmarking (CapturePcap4jCli).
- live ? Full capture + assemble + persist pipeline (LiveCli).
- ssemble ? Offline reassembly of stored segments (AssembleCli).
- poster ? Render assembled conversations (PosterCli).
- segbingrep ? Search .segbin payloads for byte sequences (	ools.SegbinGrepCli).

## Responsibilities
- Parse key=value arguments and flags via CliInput/CliArgsParser.
- Validate inputs and print helpful usage/syntax messages.
- Configure OpenTelemetry exporters via TelemetryConfigurator.
- Enable DEBUG logging with --verbose through LoggingConfigurator.
- Exit with explicit, documented codes (ExitCode).

## Metrics
The CLI layer configures exporters but does not emit metrics directly; downstream use cases rely on MetricsPort. New subcommands must keep telemetry configuration consistent.

## Testing
- CLI behaviour is covered by tests under src/test/java/ca/gc/cra/radar/api/** (argument parsing, dry run flows, exit codes).
- Tests assert usage text, error handling, and telemetry configuration when metrics options are supplied.

## Extending
- Add new subcommands by updating Main and providing a dedicated *Cli implementation.
- Document new options in the root README and Ops Runbook.
- Maintain Javadoc for public types/methods to keep Javadoc builds warning-free.
