# RADAR External Adapters

## Purpose
Houses integrations that extend RADAR beyond the built-in infrastructure, currently focused on Kafka pipelines.

## Kafka Module
- `HttpKafkaPersistenceAdapter` / `Tn3270KafkaPersistenceAdapter` — Publish assembled message pairs as JSON to Kafka topics.
- `KafkaSegmentReader` / `KafkaSegmentRecordReaderAdapter` — Consume `.segbin` records from Kafka when assembling or posting via streaming sources.
- `KafkaPosterOutputAdapter` — Emits poster outputs to Kafka topics instead of files.

## Metrics
Kafka adapters rely on application-layer metrics (`live.persist.*`, `assemble.pairs.persisted`) and add sink-specific logs. When adding new adapters, emit counters for retries, failures, and publish latencies via `MetricsPort`.

## Testing
- Use embedded Kafka test fixtures or mock producers in unit tests to minimise dependencies.
- Validate serialization formats remain stable; update Upgrade Guide when message schemas change.

## Extending
- Keep Kafka-specific configuration under `PosterConfig`/`CaptureConfig` to avoid leaking broker details into the domain.
- Follow the same pattern for future external adapters (e.g., OpenSearch, Kinesis) by placing them under this module and wiring via `CompositionRoot`.

