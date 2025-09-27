# RADAR Domain Module

## Purpose
Defines immutable capture, flow, network, and protocol models that power the capture → assemble → sink pipeline. Domain types include `SegmentRecord`, `TcpSegment`, `FiveTuple`, `ByteStream`, `MessageEvent`, and `MessagePair`.

## Responsibilities
- Encode business rules (e.g., TCP flag handling, flow direction inference) without referencing infrastructure or frameworks.
- Provide value objects for ports and adapters; the domain contains no IO or logging.
- Offer pure utilities (`FlowDirectionService`) that can be unit-tested in isolation.

## Integration Points
- Application layer (`ca.gc.cra.radar.application`) consumes these types through hexagonal ports.
- Infrastructure adapters translate external data (pcap, Kafka, files) into domain objects before invoking use cases.

## Testing
- Unit tests live under `src/test/java/ca/gc/cra/radar/domain/**` and must cover all invariants.
- Avoid mocks; prefer concrete domain instances with deterministic data.

## Metrics
- Domain code does not emit metrics directly. Metrics are recorded by the application layer once domain objects cross ports.

## Extending
- Introduce new domain records only when the concept is protocol-agnostic.
- Keep constructors validating invariants and favour factory methods for complex assembly.
- Document public types with Javadoc (required for every public class or method).

