# RADAR Validation Utilities

## Purpose
Reusable validation helpers (`Strings`, `Numbers`, `Net`, `Paths`) enforce OWASP/CERT Java guidelines for CLI input before pipelines start.

## Responsibilities
- Reject non-printable characters, control sequences, or dangerous patterns in user-supplied values (BPF expressions, Kafka topics, resource attributes).
- Enforce numeric bounds for capture tuning (snaplen, buffer sizes, rollMiB, persistWorkers/queueCapacity).
- Validate host/port combinations and filesystem paths (writable directories, create-if-missing flags).

## Usage
- Configuration records (`CaptureConfig`, `PosterConfig`) call these helpers during construction; adapters must not bypass them.
- When adding new CLI options, extend validation helpers instead of duplicating checks in multiple modules.

## Testing
- Tests in `src/test/java/ca/gc/cra/radar/validation/**` cover success and failure cases. Add coverage for every new rule.

## Extending
- Keep helpers side-effect free and thread-safe.
- Document new validation rules in Ops Runbook and Upgrade Guide if they introduce behavioural changes.

