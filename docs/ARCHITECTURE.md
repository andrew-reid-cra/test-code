# RADAR Architecture

## Hexagonal Architecture
RADAR enforces ports-and-adapters boundaries to keep the domain core independent from infrastructure concerns.
- **Domain (ca.gc.cra.radar.domain)** ? Immutable models (segments, flows, protocol events) and pure services such as FlowDirectionService.
- **Application (ca.gc.cra.radar.application)** ? Use cases (SegmentCaptureUseCase, LiveProcessingUseCase, AssembleUseCase, PosterUseCase) plus ports that describe required I/O (PacketSource, FrameDecoder, PersistencePort, MetricsPort, ProtocolDetector).
- **Adapters (ca.gc.cra.radar.infrastructure, ca.gc.cra.radar.adapter, ca.gc.cra.radar.api)** ? Implement the ports with libpcap/pcap4j/JNR capture, file and Kafka persistence, protocol modules, and CLI wiring. OpenTelemetryMetricsAdapter provides observability without polluting the core.
- **Composition (ca.gc.cra.radar.config)** ? CompositionRoot translates configuration into wired dependency graphs so each CLI remains thin.

## Package Map
| Package | Responsibility |
| --- | --- |
| domain.capture | Segment record definitions and rotation constants.
| domain.flow | Flow orientation and direction heuristics.
| domain.msg | Reconstructed message events and paired messages.
| domain.protocol | Protocol identifiers and TN3270 domain types.
| pplication.port | Hexagonal ports for capture, assembly, persistence, metrics, TN3270 assembler, and clocks.
| pplication.pipeline | Orchestrates capture, live, assemble, and poster pipelines.
| pplication.util | Pairing helpers, EMAs, and batching utilities.
| infrastructure.capture.{live|file|pcap} | Packet sources for libpcap JNI, pcap4j, and offline replay.
| infrastructure.net | Flow assemblers, frame decoders, buffer pools.
| infrastructure.protocol.{http|tn3270} | Protocol detection, reconstruction, pairing, Kafka poster.
| infrastructure.persistence.{segment|http|tn3270} | File-based persistence adapters and decorators.
| dapter.kafka | Kafka-based sinks and readers.
| infrastructure.metrics | OpenTelemetry bootstrap and MetricsPort implementation.
| pi | CLI entry points, argument parsing, telemetry bootstrap, logging setup.
| config | Configuration records, validation, and the CompositionRoot.

## Data Flow Overview
1. **Capture** ? A PacketSource (libpcap JNI or pcap4j) emits RawFrames which the FrameDecoder converts to TcpSegments.
2. **Filtering** ? SegmentCaptureUseCase discards pure ACKs, persists remaining segments, and records capture.segment.* metrics.
3. **Assembly** ? FlowProcessingEngine reorders bytes, detects protocols, and yields MessagePairs via reconstructor/pairing factories.
4. **Persistence** ? File and Kafka adapters implement PersistencePort/SegmentPersistencePort to write segments or pairs, emitting live.persist.* and ssemble.* metrics.
5. **Poster** ? Optional pipeline renders persisted conversations to analysis-friendly outputs or Kafka topics.

## Concurrency Model
- Live capture spins a fixed executor sized by persistWorkers (default max(2, cores/2)) and a bounded queue (persistQueueCapacity, default workers * 128).
- Enqueue attempts back off after 200 ?s sleeps and drop after 10 ms, incrementing live.persist.enqueue.retry or live.persist.enqueue.dropped.
- Worker threads batch up to 32 message pairs, record latency histograms (live.persist.latencyNanos, live.persist.latencyEmaNanos), and maintain a high-water gauge.
- Shutdown drains queues, joins workers with a five-second timeout, and escalates to live.persist.shutdown.force metrics when forced.
- Flow assembly itself is single-threaded per pipeline to maintain ordering; protocol adapters are responsible for internal thread safety.

## Capture Pipelines
### Live (JNI / libpcap)
`mermaid
flowchart TD
  CLI[Capture CLI] -->|config| CompositionRoot
  CompositionRoot --> PcapJNI[PcapPacketSource (JNI)]
  PcapJNI --> FrameDecoderLibpcap[FrameDecoderLibpcap]
  FrameDecoderLibpcap --> CaptureUseCase
  CaptureUseCase --> SegmentSink[SegmentFileSinkAdapter | KafkaSink]
  CaptureUseCase --> Metrics[MetricsPort -> OTel]
`

### Live (pcap4j)
`mermaid
flowchart TD
  CLI[Capture-pcap4j CLI] --> CompositionRoot
  CompositionRoot --> Pcap4j[Pcap4jPacketSource]
  Pcap4j --> FrameDecoderLibpcap
  FrameDecoderLibpcap --> CaptureUseCase
  CaptureUseCase --> SegmentSink
  CaptureUseCase --> Metrics
`

### Offline (pcap replay)
`mermaid
flowchart TD
  CLI[Capture CLI (pcapFile=...)] --> CompositionRoot
  CompositionRoot --> PcapFileSource[PcapFilePacketSource]
  PcapFileSource --> FrameDecoderLibpcap
  FrameDecoderLibpcap --> CaptureUseCase
  CaptureUseCase --> SegmentSink
  CaptureUseCase --> Metrics
`

## Sink Pipelines
### File I/O
`mermaid
flowchart LR
  Pairs[MessagePair batches] --> FilePersistence[HttpSegmentSinkPersistenceAdapter]
  Pairs --> TnFilePersistence[Tn3270SegmentSinkPersistenceAdapter]
  FilePersistence --> RotatingFiles[Rotating HTTP/TN directories]
  TnFilePersistence --> RotatingFiles
  RotatingFiles --> Operators
`

### Kafka
`mermaid
flowchart LR
  Pairs[MessagePair batches] --> HttpKafka[HttpKafkaPersistenceAdapter]
  Pairs --> TnKafka[Tn3270KafkaPersistenceAdapter]
  HttpKafka --> KafkaTopics[Kafka topics]
  TnKafka --> KafkaTopics
  KafkaTopics --> DownstreamConsumers
`

## End-to-End Pipelines
### Live (Single Process)
`mermaid
sequenceDiagram
  participant NIC as PacketSource
  participant Decoder as FrameDecoder
  participant Flow as FlowProcessingEngine
  participant Queue as Persistence Queue
  participant Workers as Persistence Workers
  participant Sink as PersistencePort
  NIC->>Decoder: poll()
  Decoder->>Flow: TcpSegment
  Flow->>Flow: reorder, detect protocol
  Flow->>Queue: enqueue MessagePair batch
  Queue-->>Flow: ack/drop
  Workers->>Sink: persist(batch)
  Workers->>OTel: metrics.observe()/increment()
`

### Three-Process Mode (capture ? assemble ? poster)
`mermaid
flowchart LR
  CaptureCLI[Capture CLI] -->|segments| SegmentDir[Segment Directory]
  AssembleCLI[Assemble CLI] -->|reads| SegmentDir
  AssembleCLI -->|pairs| PairDir[Pair Directory]
  PosterCLI[Poster CLI] -->|reads| PairDir
  PosterCLI --> Reports[Rendered reports/Kafka topics]
`

## Protocol Flows
### HTTP Reconstruction
`mermaid
sequenceDiagram
  participant Flow as FlowProcessingEngine
  participant HttpModule as HttpProtocolModule
  participant Recon as HttpMessageReconstructor
  participant Pairing as HttpPairingEngine
  Flow->>HttpModule: detect HTTP
  HttpModule->>Recon: create reconstructor
  HttpModule->>Pairing: create pairing engine
  Flow->>Recon: ByteStream slice
  Recon->>Pairing: MessageEvent
  Pairing-->>Flow: MessagePair (optional)
  Flow->>Metrics: increment live.protocol.detected / observe protocol.http.bytes
`

### TN3270 Reconstruction
`mermaid
sequenceDiagram
  participant Flow as FlowProcessingEngine
  participant TnModule as Tn3270ProtocolModule
  participant Recon as Tn3270MessageReconstructor
  participant Pairing as Tn3270PairingEngine
  participant Assembler as Tn3270AssemblerAdapter
  Flow->>TnModule: detect TN3270
  TnModule->>Recon: create reconstructor
  TnModule->>Pairing: create pairing engine
  Flow->>Recon: ByteStream slice
  Recon->>Pairing: Tn3270 events
  Pairing-->>Flow: MessagePair list
  Flow->>Assembler: forward MessagePair for screen/session enrichment
  Assembler->>Metrics: record tn3270.events.*, tn3270.parse.latency.ms
  Assembler->>Kafka/File: emit renders or submits
`

## Extensibility Points
- **Protocols** ? Register a ProtocolId, supply ProtocolModule, MessageReconstructor, and PairingEngine factories in CompositionRoot. Update telemetry and docs with new metric namespaces.
- **Capture Strategies** ? Implement PacketSource and FrameDecoder, wire them through CaptureConfig, and document new flags. Emit capture.* metrics for errors and throughput.
- **Persistence Sinks** ? Implement PersistencePort or SegmentPersistencePort, honour batching semantics, and emit live.persist.* or ssemble.* counters/histograms.
- **Telemetry** ? Provide an alternate MetricsPort implementation if integrating with a different monitoring stack. Preserve naming conventions and attach adar.metric.key attributes.
- **Buffering** ? Reuse infrastructure.net buffer pools when building new adapters to avoid heap churn.

Every extension must include unit tests, telemetry updates, and documentation changes (README, guides, diagrams) per the RADAR meta-prompt.
