# RADAR External Adapters

## Purpose
Hosts integrations that extend RADAR beyond the built-in infrastructure adapters. Today this module focuses on Kafka sinks and utilities that operate alongside the core pipelines.

## Kafka Components
- HttpKafkaPersistenceAdapter / Tn3270KafkaPersistenceAdapter ? Publish assembled message pairs as JSON to Kafka topics.
- KafkaSegmentReader / KafkaSegmentRecordReaderAdapter ? Consume .segbin records from Kafka to feed assemblers or posters.
- KafkaPosterOutputAdapter ? Emit poster results to Kafka instead of the filesystem.

## Metrics
Kafka adapters rely on application-layer metrics (live.persist.*, ssemble.pairs.persisted) and add sink-specific logs. When adding new adapters, emit counters for retries, failures, and publish latency via MetricsPort, then document them in the Telemetry Guide.

## Testing
- Use embedded Kafka fixtures or stub producers in unit tests to avoid external dependencies.
- Assert serialization schemas and topic naming; update the Upgrade Guide whenever message formats change.

## Extending
- Keep Kafka-specific configuration under CaptureConfig, LiveConfig, or PosterConfig so the domain remains unaware of brokers.
- Follow the same structure for future external integrations (OpenSearch, Kinesis, etc.) by placing them here and wiring them through CompositionRoot.
- Ensure new adapters honour backpressure and emit telemetry consistent with existing namespaces.
