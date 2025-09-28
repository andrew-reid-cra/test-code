# Logging and Error Handling

RADAR CLIs and pipelines now rely on SLF4J with a logback backend. This section explains how to
interpret logs, adjust verbosity, and understand the unified exit codes.

## Log levels
- **ERROR** - unrecoverable failures; stack traces included. Indicates the CLI will exit with a non-zero code.
- **WARN** - recoverable issues (e.g., dropped frames) that deserve attention but do not stop processing.
- **INFO** - lifecycle milestones and high-level progress (startup, shutdown, counts, resource closures).
- **DEBUG** - verbose diagnostics (per-flow or per-segment information) useful during investigations.

Log entries include mapped diagnostic context (MDC) keys such as `pipeline`, `protocol`, or `flowId`
when available to simplify correlation across threads.

## Adjusting verbosity
- Default runtime level is `INFO` via `logback.xml`.
- Set the environment variable `RADAR_LOG_LEVEL=DEBUG` (or another level) before launching a CLI to
  override the root level.
- Pass `--verbose` on any CLI (including the `radar` dispatcher) to elevate logging to `DEBUG` for the
  lifetime of the process.

## Exit codes
All CLIs exit with consistent codes:

| Code | Constant           | Meaning                                  |
|-----:|--------------------|------------------------------------------|
|   0  | `SUCCESS`          | Completed without errors                 |
|   2  | `INVALID_ARGS`     | Missing/invalid CLI arguments; usage printed |
|   3  | `IO_ERROR`         | I/O failure (filesystem, network, Kafka) |
|   4  | `CONFIG_ERROR`     | Configuration validation failed          |
|   5  | `RUNTIME_FAILURE`  | Unexpected runtime exception             |
| 130  | `INTERRUPTED`      | Process interrupted (Ctrl+C / signal)    |

Use these codes in scripts or orchestration tooling to branch on failure modes.

## Example log sequences
```
2025-09-19T18:02:41.812-04:00 [main] INFO  ca.gc.cra.radar.api.Main - Live pipeline packet source started with 4 persistence workers
2025-09-19T18:05:12.447-04:00 [live-worker-2] ERROR ca.gc.cra.radar.application.pipeline.LiveProcessingUseCase - Persistence worker live-worker-2 failed
2025-09-19T18:05:12.448-04:00 [main] DEBUG ca.gc.cra.radar.application.pipeline.LiveProcessingUseCase - Live pipeline terminating after 124 frames and 37 pairs due to failure
```

```
2025-09-19T17:10:05.101-04:00 [main] INFO  ca.gc.cra.radar.api.AssembleCli - Assemble pipeline completed for input ./cap-out
2025-09-19T17:10:05.203-04:00 [main] INFO  ca.gc.cra.radar.application.pipeline.AssembleUseCase - Assemble pipeline flushed 820 message pairs from 945 segments
```

Use `--help` on any CLI for full usage guidance. Combine the log outputs with the exit codes above to
determine next steps (e.g., retry, inspect configuration, capture diagnostics).

