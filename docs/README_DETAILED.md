# RADAR Detailed Notes

## Capture Layer
- PcapPacketSource (JNI/libpcap) and Pcap4jPacketSource (pcap4j) open capture handles (via infrastructure.capture.*) and emit RawFrame objects.
- Frames are decoded into TCP segments; SegmentIoAdapter.Writer persists them to SegmentBinIO files so assembling can run offline.

## Flow Processing
- ReorderingFlowAssembler buffers segments per flow/direction using a TreeMap keyed by sequence number. Dupes/retransmissions are trimmed; contiguous bytes are emitted as ByteStreams.
- FlowProcessingEngine orients flows, performs protocol detection, and hands slices to reconstructor + pairing engine pairs.
- HTTP and TN3270 modules live under infrastructure.protocol.* and share metrics hooks.

## Persistence
- HttpSegmentSinkPersistenceAdapter and Tn3270SegmentSinkPersistenceAdapter append headers/bodies to blob files and store offsets alongside metadata in NDJSON rows.
- Each message row captures timestamps, flow endpoints, transaction id, and blob offsets so the poster can jump straight to payload bytes.

## Poster Stage
- PosterUseCase streams NDJSON index files, groups entries by transaction id, loads payload slices, and writes text artifacts. Decode behaviour is controlled by PosterConfig.DecodeMode.
- HTTP bodies can be chunk- and gzip-decoded; binary payloads fall back to a hex dump. TN3270 output is always hex with ASCII gutters.

## Legacy Compatibility
- Legacy segment sinks have been removed; consumers should rely on SegmentBinIO outputs and downstream adapters.

## Extending RADAR
1. Implement a ProtocolModule that knows how to recognise and reconstruct your protocol.
2. Provide a PairingEngine if messages need correlation.
3. Wire the module into CompositionRoot so capture/assemble/poster can opt-in via configuration.

The tests under src/test/java cover each stage: flow assembler ordering, TN3270 pairing, persistence sinks, and the poster pipeline.

## Further Reading
- [Operations Runbook](ops/operations.md)
- [Developer Guide](dev/development.md)
- [Upgrade Notes](upgrade/UPGRADING.md)

