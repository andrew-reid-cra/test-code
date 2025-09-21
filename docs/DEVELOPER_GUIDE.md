# RADAR Developer Guide

This guide complements the generated Javadoc (`mvn -DskipTests javadoc:javadoc`) and sketches the moving parts you need when evolving RADAR.

## Architecture Overview
- **Hexagonal ports/adapters** – the `ca.gc.cra.radar.application.port` package defines the seams. Everything in `infrastructure` (libpcap, Kafka, files, poster adapters) is an adapter that can be swapped without touching the use cases.
- **Pipelines** – `capture`, `assemble`, and `poster` are orchestrated by small use cases under `application.pipeline`. Each pipeline receives ports via the `CompositionRoot` so they stay unit-testable.
- **Domain models** – immutable records such as `SegmentRecord`, `TcpSegment`, `ByteStream`, and `MessagePair` live in `ca.gc.cra.radar.domain`. They carry the canonical state between stages and are heavily documented.

```
                 +-------------------+            +------------------+
raw packets ---> | capture use case  | --flows--> | assemble use case| --pairs--> poster
                 +-------------------+            +------------------+
        (PacketSource / FrameDecoder)    (FlowAssembler / ProtocolModule / Persistence)
```

## Key Components
### Capture
- `PacketSource` – abstraction over live packets (`PcapPacketSource`) or stream input (Kafka).
- `FrameDecoder` – converts raw frames into `TcpSegment` objects (`FrameDecoderLibpcap`).
- `SegmentPersistencePort` – capture outputs segments to files (`SegmentFileSinkAdapter`) or Kafka (`SegmentKafkaSinkAdapter`).

### Assemble
- `ReorderingFlowAssembler` – buffers TCP segments per direction, trimming retransmissions and emitting contiguous `ByteStream`s.
- `ProtocolModule` – binds detection, reconstruction, and pairing for each protocol. HTTP and TN3270 ship with modules in `infrastructure.protocol.{http,tn3270}`.
- `MessageReconstructor` – reconstructs protocol messages from byte streams (`HttpMessageReconstructor`, `Tn3270MessageReconstructor`).
- `PairingEngine` – pairs request/response events (`HttpPairingEngineAdapter`, `Tn3270PairingEngineAdapter`).
- `PersistencePort` – stores `MessagePair`s (file blob/index sinks or Kafka JSON events).

### Poster
- `PosterPipeline` – consumes assembled pairs (file/Kafka) and renders `PosterReport`s.
- `SegmentPosterProcessor` – shared worker that reads blob/index formats and produces text reports.
- `PosterOutputPort` – writes reports to files (`FilePosterOutputAdapter`) or publishes them (`KafkaPosterOutputAdapter`).

## Protocol Details
### HTTP
- Flow assembler: `HttpFlowAssemblerAdapter` forwards non-empty payloads.
- Reconstructor: `HttpMessageReconstructor` accumulates headers/bodies, handles `Content-Length`, and tags requests/responses with a FIFO transaction id.
- Pairing: `HttpPairingEngineAdapter` assumes client -> request, server -> response.
- Persistence: `HttpSegmentSinkPersistenceAdapter` (blob/index) and `HttpKafkaPersistenceAdapter` (JSON payloads).

### TN3270
- Flow assembler: `Tn3270FlowAssemblerAdapter` forwards raw Telnet bytes.
- Reconstructor: `Tn3270MessageReconstructor` understands Telnet IAC/EOR negotiation, splits records, and tracks host-first ordering.
- Pairing: `Tn3270PairingEngineAdapter` queues host responses until a terminal request arrives.
- Persistence: `Tn3270SegmentSinkPersistenceAdapter` (blob/index) and `Tn3270KafkaPersistenceAdapter` (JSON payloads).

## Extending with a New Protocol
1. **Define identifiers** – add a `ProtocolId` entry and update enabled lists in `CompositionRoot`.
2. **Flow assembler** – implement `FlowAssembler` if TCP reassembly needs different semantics.
3. **Reconstructor** – implement `MessageReconstructor` to turn `ByteStream`s into protocol events.
4. **Pairing** – implement `PairingEngine` if request/response correlation is non-trivial.
5. **Persistence / poster** – add adapters that implement `PersistencePort` and optionally `PosterOutputPort`.
6. **ProtocolModule** – wire detection, reconstructor, pairing, and default ports into a new module and register it in `CompositionRoot`.
7. **Tests** – follow patterns in `src/test/java` (e.g., `HttpMessageReconstructorTest`, `Tn3270MessageReconstructorTest`).

## CLI Configuration Cheat-Sheet
| Pipeline | Key Args | Notes |
|----------|----------|-------|
| `capture` | `iface`, `ioMode`, `kafkaBootstrap`, `out`, `fileBase`, `rollMiB` | Use `ioMode=KAFKA` to stream segments directly to Kafka. |
| `assemble` | `in`, `ioMode`, `httpEnabled`, `tnEnabled`, `kafka*Topic`, `out` | When `in` starts with `kafka:` the use case switches to Kafka mode automatically. |
| `poster` | `httpIn`, `tnIn`, `posterOutMode`, `httpOut`, `tnOut`, `decode` | `decode=transfer` removes chunked framing; `decode=all` also decompresses bodies. |

## Build & Quality Checks
- `mvn verify` – full build and tests.
- `mvn -DskipTests javadoc:javadoc` – regenerate HTML docs (output in `target/site/apidocs`).
- `mvn -DskipTests checkstyle:check` – enforces public Javadoc coverage.

## Directory Layout
```
src/main/java/ca/gc/cra/radar/
  api/            # CLI entrypoints
  application/    # ports + pipelines
  config/         # composition root & CLI config records
  domain/         # immutable capture/protocol models
  infrastructure/ # adapters for capture, protocol modules, persistence, poster, metrics, time
```

## Useful Entry Points
- `ca.gc.cra.radar.config.CompositionRoot` – wiring for all pipelines.
- `ca.gc.cra.radar.application.pipeline.*` – orchestrators you can reuse in tests.
- `docs/Javadoc` – run `mvn -DskipTests javadoc:javadoc` and open `target/site/apidocs/index.html`.

Happy tracing!