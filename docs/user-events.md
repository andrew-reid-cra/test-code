# User Events

This module extracts structured user events from reconstructed HTTP exchanges using YAML-driven
rules. Rules specify how to match requests/responses, extract identifiers, and emit immutable
`UserEvent` records for downstream telemetry.

## Rule Files

Rules are stored in YAML documents. A rule set contains:

- `version`: currently `1`.
- `defaults`: optional metadata applied to every event (e.g., `app`, `source`).
- `rules`: ordered list of rule definitions.

Each rule supports:

- `match`: conditions on method, path, status, headers, cookies, query parameters, and body
  (including `jsonpath` expressions).
- `extract`: optional `user_id` expression and additional attributes sourced from headers, cookies,
  path regex groups, query params, or JSON bodies.
- `event`: declares the emitted `event.type` and static attributes.

See `config/user-events.example.yaml` for a annotated sample.

## Hot Reload

Rule files are polled every five seconds while the pipeline runs. Successful reloads replace the
active rule set; parse errors are logged and the previous valid configuration remains in effect.

## CLI Dry-Run

Use the `user-events` CLI command to test rules against sample HTTP exchanges without running the
full pipeline:

```bash
mvn -q exec:java -Dexec.args="user-events rules=config/user-events.yaml samples=dev/samples/http"
```

The CLI expects each sample to have `<name>-request.txt` and optional `<name>-response.txt` files in
the sample directory. Example samples are provided under `dev/samples/http`.

The command prints matched rules, extracted attributes, and a summary of events emitted.

## Runtime Integration

When HTTP persistence is enabled, the `UserEventPublishingPersistenceAdapter` decorates the sink
pipeline. It parses HTTP request/response pairs, evaluates rules, resolves sessions via
`SessionResolver`, emits events through the configured `UserEventEmitter`, and then delegates to the
underlying persistence adapter.

Metrics prefixed with `userEvents.*` record parse skips, matches, emissions, and errors.
