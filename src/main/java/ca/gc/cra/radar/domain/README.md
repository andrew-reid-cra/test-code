# RADAR Domain Module

## Purpose
Defines immutable capture, flow, network, and protocol models that underpin the capture -> assemble -> sink pipeline. Domain types include SegmentRecord, TcpSegment, FiveTuple, ByteStream, MessageEvent, and MessagePair.

## Responsibilities
- Encode business rules (TCP flag handling, flow direction inference) without referencing infrastructure or external frameworks.
- Provide value objects for ports and adapters; the domain contains no I/O, logging, or concurrency primitives.
- Offer pure utilities (for example FlowDirectionService) that can be unit-tested deterministically.

## Integration Points
- The application layer consumes these types through ports (PacketSource, FlowAssembler, PersistencePort).
- Adapters translate external data (pcap, Kafka, files) into domain objects before invoking use cases.

## Testing
- Unit tests reside under src/test/java/ca/gc/cra/radar/domain/** and must cover all invariants.
- Avoid mocks; use concrete domain instances with deterministic data sets.

## Metrics
- Domain code does not emit metrics directly. Telemetry is recorded by the application layer after domain objects cross port boundaries.

## Extending
- Introduce new domain records only when the concept is protocol-agnostic and immutable.
- Validate invariants in constructors/factories and document them with Javadoc (required for all public types).
- Keep the module dependency-free so it remains portable and easily testable.
