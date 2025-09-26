# Upgrading RADAR

This document captures behaviour changes introduced after the pipeline refactor that retired legacy
assemblers and tightened logging / security defaults.

## Deprecations and removals

- **`LegacyHttpAssembler`** - removed in 0.2.0. Migrate to the `PosterUseCase` + `HttpPosterPipeline` wiring or the protocol-specific `PosterPipeline` adapters documented below.
- **`ContextualFlowAssembler`** - removed. Implement the core `FlowAssembler` interface and propagate `FlowContext` metadata through your application ports instead of relying on the contextual extension.
- **`NoOpFlowAssembler`** - removed. Use a purpose-built `FlowAssembler` fake under `src/test` when you need a no-op implementation during testing.
- **Legacy segment persistence adapters** - filesystem-only helper classes (`LegacySegmentPersistenceAdapter`)
  have been removed. Use `SegmentIoAdapter`/`SegmentFileSinkAdapter` instead.

## Logging and exit codes

All CLIs now follow a shared exit-code contract (documented in `docs/ops/operations.md`). When
wrapping the binaries, map

- `0` ? success,
- `64` ? configuration validation failure,
- `65` ? IO/Kafka failures,
- `70` ? internal pipeline error,
- `130` ? operator interrupt.

`System.err` messages are prefixed with the CLI name (e.g., `poster:`) so central log collectors can
route by subsystem.

## Security and BPF gating

Capture defaults to a locked-down configuration: promiscuous mode on, snap length 65535, buffer 1 GiB
and **no traffic captured unless `iface` is explicit**. Always provide a BPF filter (`bpf="ip host ?"`)
when operating in multi-tenant span environments. Invalid filters abort the start-up sequence before
any packets are consumed.

## Migration examples

### HTTP poster workflow

__Before__ (legacy assembler, implicit poster):
```
legacy-http-assembler --in segments/http.ndjson --out poster/http
```

__After__ (assemble + poster pipeline):
```
radar assemble in=/data/segments out=/data/pairs httpOut=/data/http
radar poster httpIn=/data/pairs/http httpOut=/data/poster/http decode=transfer
```

### Kafka end-to-end

__Before__
```
assemble ioMode=KAFKA kafkaBootstrap=broker:9092 kafkaSegmentsTopic=radar.segments          kafkaHttpPairsTopic=radar.http.pairs
poster kafkaBootstrap=broker:9092 kafkaHttpPairsTopic=radar.http.pairs
```

__After__
```
radar assemble ioMode=KAFKA kafkaBootstrap=broker:9092                kafkaSegmentsTopic=radar.segments kafkaHttpPairsTopic=radar.http.pairs
radar poster ioMode=KAFKA posterOutMode=KAFKA kafkaBootstrap=broker:9092             httpIn=radar.http.pairs kafkaHttpReportsTopic=radar.http.reports
```

### Safe dry-run

1. Capture to a throwaway location: `radar capture iface=ens3 out=/tmp/radar/segments bpf="tcp port 443"`.
2. Assemble locally: `radar assemble in=/tmp/radar/segments out=/tmp/radar/pairs`.
3. Poster to sandbox directories: `radar poster httpIn=/tmp/radar/pairs/http httpOut=/tmp/radar/poster/http`.
4. Inspect results, then repeat with production paths or Kafka topics.

## Verification

After migrating, run the following to ensure the environment is healthy:

```
mvn -q clean verify
mvn -q site
```

Review `target/site/index.html` for coverage and test-report regressions before promoting the build.
