# TN3270 Screen Events

TN3270 screen events decorate assembler output with semantic context so downstream systems can
trace operator activity, audited inputs, and host responses. The feature evaluates configured screen
rules against render events, caches the latest matching screen per session, and emits `TerminalEvent`
records when the operator submits input.

## Rule File Structure

Rules are defined in YAML documents and loaded in the order they are provided to the runtime
(`TN3270_SCREENS_RULES` environment variable or default config paths). Every document must declare:

- `version`: currently only `1` is supported.
- `screens`: ordered list of screen rule objects evaluated top to bottom.

Each entry under `screens` contains:

- `id` (required): unique identifier. Duplicate ids across files fail startup.
- `description` (optional): human-friendly summary surfaced in telemetry.
- `match` (required): one or more match conditions that must all succeed.
- `label` (required): extraction definition for the rendered screen label.
- `userId` (optional): extraction definition for the operator identifier.

```yaml
version: 1
screens:
  - id: SIGNON
    description: Initial sign-on prompt requesting operator credentials.
    match:
      - position:
          row: 1
          column: 1
          length: 24
        equals: "CONNECT TO HOST"
        trim: true
        ignoreCase: true
    label:
      position:
        row: 1
        column: 1
        length: 32
      trim: true
    userId:
      position:
        row: 6
        column: 18
        length: 8
      trim: true
```

### Match Conditions

Match conditions operate on rectangular slices of the terminal buffer using one-based coordinates.
`position.row`, `position.column`, and `position.length` must each be positive integers. When the
`position` object is omitted, the fields may be specified directly under the match object. Additional
options:

- `trim` (default `true`): remove surrounding whitespace before comparison.
- `ignoreCase` (default `false`): perform the comparison case-insensitively.
- `equals` (required): literal string to compare. Blank values are rejected during load.

All match expressions must succeed for the rule to fire. The engine returns the first rule that
matches, so place the most specific conditions first.

### Label and Operator Extraction

`label` and `userId` use the same position schema. Extracted text is trimmed by default. The `label`
feeds `screen.label` attributes on emitted terminal events and is mandatory. `userId` populates
`terminal.operator.id` when present. Screens without a `userId` extraction still emit events; the
assembler falls back to the submitter id carried in the TN3270 event when available.

## Defaults and Validation

- Rule files with an unsupported `version` or missing required fields cause launch failures.
- Duplicate screen ids across merged files abort loading to prevent ambiguous matches.
- If no rule files resolve (environment variable empty and no defaults found) the feature is
  disabled and the assembler logs that screen rules are unavailable.
- Screen coordinates are validated to ensure they are within bounds (`>= 1`).
- Runtime trimming defaults (`trim: true`) avoid false negatives caused by padded fields.

## Usage

1. Copy `config/tn3270-screens.example.yaml` to `config/tn3270-screens.yaml` and tailor the rules.
2. Launch assemble or live pipelines with `--tn3270-screens config/tn3270-screens.yaml` or set
   `TN3270_SCREENS_RULES=/opt/radar/tn3270-screens.yaml`. Multiple files may be supplied by separating
   paths with commas or semicolons; later files are appended.
3. Restart the pipeline after modifying rule files. Hot reload is not yet implemented for TN3270
   screens (tracked in RADAR-2761).
4. Verify configuration with `mvn -q test -Dtest=ScreenRuleDefinitionsLoaderTest` before deployment to
   catch schema regressions.

At runtime, matching screens are cached per session to avoid redundant evaluations. Submit events are
annotated with the cached screen context, and the cache is cleared when sessions terminate.

## Telemetry

The publishing sink emits OpenTelemetry metrics via the configured `MetricsPort`. Metrics share the
prefix supplied at construction (`tn3270Terminals.publisher` by default):

- `<prefix>.render.captured`: render events observed with snapshots.
- `<prefix>.render.match` / `.render.noMatch` / `.render.rulesDisabled`: rule evaluation outcomes.
- `<prefix>.render.error`: failures while evaluating rules.
- `<prefix>.submit.received`: TN3270 submit events processed.
- `<prefix>.submit.match` / `.submit.noMatch` / `.submit.skipped.noScreen`: screen context available
  when submits arrive.
- `<prefix>.processing.error`: unexpected runtime exceptions while handling events.
- `<prefix>.emitted`: terminal events successfully forwarded.
- `<prefix>.attributes.size`: emitted attribute count recorded as a distribution metric.
- `<prefix>.emit.error`: failures when forwarding terminal events downstream.

Review `docs/observability.md` for exporter configuration and ensure new metrics are added to
monitoring dashboards.

## Change Management

Screen rule updates must ship with accompanying unit tests and documentation. Update this guide,
`config/tn3270-screens.example.yaml`, and the project changelog when introducing new matching
capabilities or telemetry. Follow the conventional commit process and run the full Maven test suite
before merging.
