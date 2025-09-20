# RADAR Capture Pipeline

RADAR ingests raw packets, reassembles TCP flows, and emits readable HTTP and TN3270 conversations. The architecture follows a hexagonal pattern: adapters expose packet capture, flow assembly, protocol modules, pairing engines, and persistence sinks through small ports so each stage stays testable.

## Pipeline At A Glance
- **capture** – wraps libpcap via the JNR adapter, applies an optional BPF filter, and writes every TCP segment to rotating `.segbin` files.
- **assemble** – reads those segment files, reorders TCP by flow, detects HTTP and TN3270 traffic, reconstructs protocol messages, and writes index/blob pairs under `out/http/` and `out/tn3270/`.
- **poster** – walks the NDJSON index files for a protocol, pairs request/response parts by transaction id, optionally decodes transfer/content encodings, and produces friendly `.http` (or `.tn3270.txt`) summaries per transaction.

## Key Concepts
- **SegmentBin format** – each capture file starts with the `SGB1` magic followed by length‑prefixed segment records (timestamp, flow tuple, flags, payload). Writers rotate by MiB as configured.
- **ReorderingFlowAssembler** – buffers out-of-order TCP data per direction, trims retransmissions, and emits contiguous byte streams as soon as gaps fill.
- **Protocol modules** – HTTP and TN3270 reconstructors share the same flow engine and metrics hooks; pairing engines correlate reconstructed messages into `MessagePair`s for persistence.
- **Persistence** – HTTP and TN3270 adapters stream bytes into large blob files and record offsets in an NDJSON index so the poster can fetch payloads efficiently without many small files.

## CLI Examples
Commands are passed as `key=value` pairs. Omit options you do not need; defaults match the ones shown.

```bash
# 1. capture segments to ./cap-out
java -jar radar.jar capture \
  iface=eth0 snaplen=65535 promiscuous=true timeout=1000 \
  bufferBytes=65536 immediate=true \
  out=./cap-out base=capture rollMiB=512 \
  bpf="(tcp and port 80) or (tcp and port 23)"

# 2. assemble HTTP + TN3270 conversations
java -jar radar.jar assemble \
  in=./cap-out \
  out=./pairs-out \
  httpEnabled=true tnEnabled=true

# 3. render human-readable transactions (run once per protocol directory)
java -jar radar.jar poster \
  in=./pairs-out/http \
  out=./reports-http \
  decode=all

java -jar radar.jar poster \
  in=./pairs-out/tn3270 \
  out=./reports-tn \
  decode=none
```

- `decode=none|transfer|all` controls how poster handles `Transfer-Encoding` and `Content-Encoding` headers. `transfer` unwraps chunked bodies; `all` additionally inflates gzip/deflate payloads.
- Poster writes files named `<timestamp>_<id>.http` (or `.tn3270.txt`). Binary payloads are shown as hex dumps; text bodies remain in ISO‑8859‑1 to preserve bytes.

## Output Layout
```
cap-out/                # raw segment files (*.segbin)
pairs-out/
  http/
    blob-*.seg          # concatenated HTTP headers/bodies
    index-*.ndjson      # per message metadata
  tn3270/
    blob-tn-*.seg       # binary TN3270 messages
    index-tn-*.ndjson
reports-http/           # poster output (.http)
reports-tn/             # poster output (.tn3270.txt)
```

## Building & Testing
RADAR targets Java 17 and Maven. Run `mvn test` to execute the unit suite (no external capture device required). The repository ships with JNR bindings for libpcap; on systems without libpcap available you can still run assemble/poster stages against existing segment files.

## Next Steps
- Extend `ReorderingFlowAssembler` or add adapters for new protocols by wiring a reconstructor + pairing engine into `CompositionRoot`.
- Customize persistence by implementing `PersistencePort` or the poster output if you need alternate artifact formats.
- Review `src/test/java/...` for focused tests covering flow reassembly, TN3270 pairing, persistence sinks, and the poster pipeline.
