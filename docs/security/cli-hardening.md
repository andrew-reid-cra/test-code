# RADAR CLI Hardening

## Input Validation Summary

| CLI | Flag | Constraint |
| --- | --- | --- |
| capture/live | `iface` | `Strings.requireNonBlank`; trimmed; rejects control characters |
| capture/live | `out` | `Paths.validateWritableDir`; canonical path inside optional base; rejects null bytes; `--allow-overwrite` required for non-empty |
| capture/live | `snap` | `Numbers.requireRange` (64-262144) |
| capture/live | `bufmb` | `Numbers.requireRange` (4-4096 MiB) |
| capture/live | `timeout` | `Numbers.requireRange` (0-60000 ms) |
| capture/live | `kafkaBootstrap` | `Net.validateHostPort` (hostname/IPv4/IPv6 + port) |
| capture/live | `kafka*Topic` | `Strings.sanitizeTopic` (`[A-Za-z0-9._-]+`) |
| capture/live | `bpf` | `Strings.requirePrintableAscii` (=1024 bytes); rejects control/`;`/`` ` ``; requires `--enable-bpf` |
| assemble/poster | `in` | File mode requires existing readable directory; Kafka mode requires valid topic |
| assemble/poster | `out`/protocol outputs | `Paths.validateWritableDir` enforcing canonical, writable directories |
| assemble/poster | `kafkaBootstrap` | `Net.validateHostPort` |
| assemble/poster | `kafka*Topic` | `Strings.sanitizeTopic` |
| segbingrep | `needle` | `Strings.requireNonBlank` + printable ASCII check |
| segbingrep | `ctx` | `Numbers.requireRange` (1-4096) |

`CliArgsParser` rejects control characters, null bytes, and non `key=value` pairs for all CLIs. All collections exposed to callers use fully parameterized generics (`Map<String, String>`, `Optional<Path>`, etc.).

## Safe Defaults

* Output directories default to `~/.radar/out/...` and are canonicalized via `Paths.validateWritableDir` to prevent traversal outside the sandbox. Directories are created only when running without `--dry-run`.
* File overwrites are disabled unless the operator supplies `--allow-overwrite`; otherwise non-empty directories are rejected.
* Worker counts and queue capacities derive from CPU count and are bounded (`persistWorkers` 1-32, `persistQueueCapacity` =65536) to avoid unbounded memory growth. Errors clearly state required ranges.
* Capture pipelines ship with a conservative default BPF filter (`tcp`). No packets are captured unless the interface is explicitly supplied, and the filter stays in place unless overridden.
* `PosterCli` and `AssembleCli` require explicit IO mode configuration for Kafka and will not emit to Kafka without validated bootstrap servers and topics.

Operators can override defaults using validated flags; all overrides follow the constraints documented above.

## BPF Risk Gate

Custom BPF expressions are disabled by default. Operators must:

1. Pass `--enable-bpf` to acknowledge the risk.
2. Provide `--bpf "expr"` using printable ASCII =1024 bytes. Expressions containing `;` or `` ` `` are rejected to avoid shell injection.
3. Review logs: enabling custom BPF emits a `SECURITY` warning and logs a truncated filter preview. This event should be captured by operational monitoring.

Recommended guardrails:

* Keep a run-book entry describing approved expressions.
* Review BPF changes during change control and log aggregation.
* Prefer the default `tcp` filter when possible.

## Secure Invocation Examples

```bash
# Dry-run capture to validate configuration without touching disk
radar capture iface=en0 out="/srv/radar/capture" --dry-run

# Live capture streaming to Kafka with explicit acknowledgement
radar live iface=eth1 out=kafka:radar.capture --enable-bpf --bpf "tcp port 443"

# Assemble pipeline reading segments from disk into sandboxed output
radar assemble in="/srv/radar/capture" out="/srv/radar/assemble" --dry-run

# Poster rendering with strict file outputs
radar poster httpIn="/srv/radar/http" httpOut="/srv/radar/poster/http" --dry-run
```

Use `--help` on every CLI to view the validated ranges and patterns before execution.

## Coding Standards

* All validation and CLI-facing utilities must use parameterized generics. Raw `List`, `Map`, or `Optional` types are forbidden; prefer `List<String>`, `Map<String, Path>`, etc.
* Perform input validation through the shared `ca.gc.cra.radar.validation` helpers. Introducing new ad-hoc parsing or unchecked casts requires code review approval.
* Never log sensitive payloads directly. Use `Logs.truncate` for bounded output and introduce `Logs.redact` for future secret fields.
* Maintain `--dry-run` pathways for destructive CLIs so operators can validate arguments before execution.
* Document any new flags or constraints in this guide and ensure unit tests cover both valid and invalid scenarios.
