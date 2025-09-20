Refactoring Plan Table
(old package/class → new location · role · notes)

sniffer.app

sniffer.app.CaptureMain → ca.gc.cra.radar.api.CaptureCli · CLI entry (segment capture) · wraps CompositionRoot capture use-case
sniffer.app.CaptureRunner → ca.gc.cra.radar.api.LiveCli · CLI entry (live decode) · reuse pipeline wiring
sniffer.app.CliConfig → ca.gc.cra.radar.api.CliArgsParser · CLI args → CaptureConfig/LiveConfig DTOs
sniffer.poster.PosterMain → ca.gc.cra.radar.api.PosterCli · CLI entry (post-processing)
sniffer.capture

CaptureMain capture loop → ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase (application) + ca.gc.cra.radar.infrastructure.capture.PcapPacketSource (adapter)
CaptureConfig → ca.gc.cra.radar.config.CaptureConfig (record)
TcpDecoder → ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap (implements FrameDecoder port)
capture.properties → resources/config/default-capture.properties (unchanged values, new location)
sniffer.domain

CaptureLoop → split
• ca.gc.cra.radar.application.pipeline.LiveProcessingUseCase (application orchestrator)
• ca.gc.cra.radar.domain.flow.FlowDirectionService (domain helper, pure logic)
SegmentSink → ca.gc.cra.radar.infrastructure.persistence.SegmentSinkAdapter (implements PersistencePort)
SessionIdExtractor → ca.gc.cra.radar.infrastructure.protocol.http.HttpSessionExtractor (adapter helper)
HttpAssembler → decomposed into
• ca.gc.cra.radar.infrastructure.protocol.http.HttpFlowAssemblerAdapter (implements FlowAssembler port)
• ca.gc.cra.radar.infrastructure.protocol.http.Http1Reconstructor (MessageReconstructor impl)
• ca.gc.cra.radar.application.util.HttpMessageSession (extends AbstractMessageSession)
HttpPrinter merge into Http1Reconstructor logging util
HttpIds → ca.gc.cra.radar.domain.msg.TransactionId (domain value object)
tn3270.* → reorganize
• TelnetRecordDecoder → ca.gc.cra.radar.infrastructure.protocol.tn3270.TelnetRecordDecoder
• Tn3270Session → ca.gc.cra.radar.application.util.Tn3270MessageSession (MessageSession impl)
• Tn3270Assembler → ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270Reconstructor (MessageReconstructor)
• Tn3270Screen → ca.gc.cra.radar.domain.protocol.tn3270.ScreenBuffer (domain)
sniffer.pipe

SegmentRecord → ca.gc.cra.radar.domain.capture.SegmentRecord
SegmentIO.Writer/Reader → ca.gc.cra.radar.infrastructure.persistence.SegmentIoAdapter (PersistencePort + PacketSource support)
sniffer.spi/adapters.libpcap

Move under ca.gc.cra.radar.infrastructure.capture.libpcap.* implementing PacketSource port
sniffer.assemble.AssembleMain

AssembleMain → ca.gc.cra.radar.api.AssembleCli + ca.gc.cra.radar.application.pipeline.AssembleUseCase
sniffer.poster.*

PosterMain logic → ca.gc.cra.radar.application.pipeline.PosterUseCase (application)
Pairing logic uses new MessagePairer + PairingEngine port implementations (HTTP/TN modules)
sniffer.tools.*

Evaluate individually:
• SegbinGrep → keep as ca.gc.cra.radar.api.tools.SegbinGrepCli if still useful
• others flagged for deletion via safe-delete workflow if unused
configs/docs

README.md update references; docs/* remain as documentation (no package change)
New abstractions/locations

ca.gc.cra.radar.application.port.* (PacketSource, FrameDecoder, FlowAssembler, ProtocolDetector, MessageReconstructor, PairingEngine, PersistencePort, MetricsPort, ClockPort, ProtocolModule)
ca.gc.cra.radar.application.pipeline.* (SegmentCaptureUseCase, LiveProcessingUseCase, AssembleUseCase, PosterUseCase)
ca.gc.cra.radar.application.util.* (MessagePairer, AbstractMessageSession, protocol-specific sessions)
ca.gc.cra.radar.domain.* (net.* for RawFrame, TcpSegment, ByteStream; msg.* for MessageEvent, MessagePair; capture.* for SegmentRecord; protocol.* for HTTP/TN domain models)
ca.gc.cra.radar.infrastructure.protocol.http.* / .tn3270.* (ProtocolModule implementations, reconstructor adapters)
ca.gc.cra.radar.infrastructure.persistence.* (SegmentSinkAdapter, SegmentIoAdapter)
ca.gc.cra.radar.infrastructure.metrics.*, .time.* (implement MetricsPort, ClockPort)
ca.gc.cra.radar.config.* (Config records, loaders, CompositionRoot)
ca.gc.cra.radar.api.Main (entrypoint orchestrating CLI selection)
Deletes/Merges noted above; final list refined during implementation.

Dead Code Candidates (initial)

Class/Resource	Evidence E1–E5	Proposed Action	Notes
sniffer.tools.SegbinGrep	E1=false (CLI doc references)	KEEP	Provide as api.tools
sniffer.domain.HttpPrinter	E1 true, consumed only by CLI printing	MERGE into Http1Reconstructor logging	
sniffer.domain.HttpIds	E1 true, no external references	MERGE into new domain TransactionId	
sniffer.domain.CaptureLoop.FlowDir/FlowKey duplicates post-refactor	to be reassessed after split	likely merged into domain flow utils	
sniffer.poster.LegacyPosterConfig (if found)	verify; candidate for delete	pending workflow	
sniffer.pipe.SegmentIO.ReaderLegacy (if exists)	verify jdeps/grep	DELETE if unused	
Full safe-delete workflow will produce evidence-backed table before removals.

Dependency Graph (target)

api
 ├─ depends on config (composition)
 └─ depends on application

config
 └─ depends on application & infrastructure (wire adapters)

application
 ├─ depends on domain
 ├─ uses application.port.*
 └─ has util helpers (pure logic depending only on domain)

domain
 └─ (no dependencies)

infrastructure
 ├─ implements application.port.*
 ├─ depends on domain (DTOs/models)
 └─ may depend on external libs (pcap, IO)

tests
 └─ can depend on api/application/domain/infrastructure (per slice) without reverse deps
Risks & Mitigations

TCP edge cases: Ensure FlowAssembler preserves existing reassembly guarantees (OOO buffering, retransmit handling). Add unit tests with pathological sequences.
HTTP pipelining/chunked: Split reconstructor must handle pipelined requests/responses; add application tests for pipelined flows and chunked bodies.
TN3270 host-first pairing: New MessageSession must respect host-first semantics; include unit tests covering Write/EraseWrite followed by AID inputs.
ProtocolDetector heuristics: Avoid misclassification; use configurable preferred ports and signature confidence; fallback to unknown when ambiguous.
Performance/GC: Added abstractions may increase allocations; leverage pooled buffers, avoid per-segment object churn. Profile critical path if needed.
Persistence consistency: SegmentSinkAdapter must emit identical NDJSON/blob format (including existing metadata keys). Regression tests via snapshot outputs.
Configuration compatibility: Maintain existing CLI flags where possible; provide migration notice for renamed options.
Safe-delete accuracy: Confirm no reflective/native loads before removal; document evidence.
