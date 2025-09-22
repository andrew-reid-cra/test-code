# RADAR Capture Pipeline

RADAR ingests raw packets, reassembles TCP flows, and emits readable HTTP and TN3270 conversations. The architecture follows a hexagonal pattern: adapters expose packet capture, flow assembly, protocol modules, pairing engines, and persistence sinks through small ports so each stage stays testable.

## Pipeline At A Glance
- **capture** – wraps libpcap (via JNR) or Kafka to gather TCP segments.
- **assemble** – reorders TCP, detects HTTP/TN3270, reconstructs protocol messages, and persists per-protocol outputs.
- **poster** – consumes the assembled outputs (files or Kafka) and renders human-readable reports.

## Key Concepts
- **SegmentBin format** – rotating `.segbin` files hold length-prefixed segment records (timestamp, flow tuple, flags, payload).
- **ReorderingFlowAssembler** – buffers out-of-order TCP data per direction, trims retransmissions, and emits contiguous byte streams as soon as gaps fill.
- **Protocol modules** – HTTP and TN3270 reconstructors plug into the flow engine; pairing engines correlate reconstructed messages into `MessagePair`s for persistence.
- **Persistence** – HTTP and TN3270 adapters either stream bytes into blob/index files (`FILE` mode) or publish structured events to Kafka (`KAFKA` mode).

## CLI Quickstart
All CLIs accept `key=value` arguments. The executable entry point is `ca.gc.cra.radar.api.Main`; the examples below assume the project has been built (`mvn -q -DskipTests package`). Replace `target/RADAR-0.1.0-SNAPSHOT.jar` with the actual jar name produced on your machine.

```bash
JAR=target/RADAR-0.1.0-SNAPSHOT.jar
```

### Minimal smoke (default FILE mode)
The commands below run end-to-end with empty inputs to verify wiring – each stage succeeds and produces the expected directory structure.

```bash
# 1) assemble an empty capture directory (creates http/ outputs by default)
java -cp $JAR ca.gc.cra.radar.api.Main assemble \
  in=tmp/assemble-in out=tmp/pairs-out

# 2) assemble with TN3270 enabled (creates tn3270/ outputs)
java -cp $JAR ca.gc.cra.radar.api.Main assemble \
  in=tmp/assemble-in out=tmp/pairs-out-tn httpEnabled=false tnEnabled=true

# 3) poster reads those outputs and ensures the report directories exist
java -cp $JAR ca.gc.cra.radar.api.Main poster \
  httpIn=tmp/pairs-out/http httpOut=tmp/poster-out/http \
  tnIn=tmp/pairs-out-tn/tn3270 tnOut=tmp/poster-out/tn3270 decode=none
```

After running the sequence you should see:

```
tmp/
  assemble-in/                     # (input you provided)
  pairs-out/
    http/
      blob-*.seg
      index-*.ndjson
  pairs-out-tn/
    tn3270/
      blob-tn-*.seg
      index-tn-*.ndjson
  poster-out/
    http/
    tn3270/
```

### Full FILE pipeline with real data
```bash
# Capture to rotating files (requires libpcap + permissions)
java -cp $JAR ca.gc.cra.radar.api.Main capture \
  iface=eth0 snaplen=65535 promiscuous=true timeout=1000 \
  bufmb=1024 immediate=true out=./cap-out fileBase=capture rollMiB=512 \
  --enable-bpf bpf="(tcp and port 80) or (tcp and port 23)"

# Assemble both protocols from files into per-protocol directories
java -cp $JAR ca.gc.cra.radar.api.Main assemble \
  in=./cap-out out=./pairs-out httpEnabled=true tnEnabled=true

# Render human-readable transactions
java -cp $JAR ca.gc.cra.radar.api.Main poster \
  httpIn=./pairs-out/http httpOut=./reports-http \
  tnIn=./pairs-out/tn3270 tnOut=./reports-tn decode=all
```

### Full FILE pipeline from PCAP

Offline capture mode replays packets from a pcap/pcapng trace and stops automatically once the file is drained. The same SegmentBin rotation settings apply, so downstream assemble/poster jobs do not require any changes.

```bash
JAR=target/RADAR-0.1.0-SNAPSHOT.jar
# 1) offline capture from PCAP -> .segbin
java -cp $JAR ca.gc.cra.radar.api.Main capture \
  pcapFile=./samples/http-small.pcap out=./cap-out fileBase=capture rollMiB=512 \
  snaplen=65535 --enable-bpf bpf="tcp and port 80"

# 2) assemble into per-protocol outputs
java -cp $JAR ca.gc.cra.radar.api.Main assemble \
  in=./cap-out out=./pairs-out httpEnabled=true tnEnabled=false

# 3) poster renders human-readable HTTP reports
java -cp $JAR ca.gc.cra.radar.api.Main poster \
  httpIn=./pairs-out/http httpOut=./reports-http decode=all
```

#### TN3270 offline capture (default filter)

```bash
JAR=target/RADAR-0.1.0-SNAPSHOT.jar
# 1) offline capture from PCAP -> .segbin (TN3270 default filter applied)
java -cp $JAR ca.gc.cra.radar.api.Main capture \
  pcapFile=./samples/tn3270-small.pcap protocol=TN3270 \
  out=./cap-tn fileBase=tn-capture rollMiB=512

# 2) assemble TN3270 conversations
java -cp $JAR ca.gc.cra.radar.api.Main assemble \
  in=./cap-tn out=./pairs-tn httpEnabled=false tnEnabled=true

# 3) poster renders TN3270 request/response blobs
java -cp $JAR ca.gc.cra.radar.api.Main poster \
  tnIn=./pairs-tn/tn3270 tnOut=./reports-tn decode=none
```

TN3270 mode automatically applies the safe filter `tcp and (port 23 or port 992)` unless `bpf="..."` is supplied (requires `--enable-bpf`).

### Kafka pipeline
```bash
# Capture directly to Kafka
java -cp $JAR ca.gc.cra.radar.api.Main capture \
  iface=eth0 ioMode=KAFKA kafkaBootstrap=localhost:9092 \
  kafkaTopicSegments=radar.segments

# Assemble from Kafka into Kafka pair topics
java -cp $JAR ca.gc.cra.radar.api.Main assemble \
  in=kafka:radar.segments ioMode=KAFKA kafkaBootstrap=localhost:9092 \
  httpEnabled=true tnEnabled=true \
  kafkaHttpPairsTopic=radar.http.pairs \
  kafkaTnPairsTopic=radar.tn3270.pairs

# Poster: consume Kafka pairs and write reports to disk
java -cp $JAR ca.gc.cra.radar.api.Main poster \
  httpIn=kafka:radar.http.pairs tnIn=kafka:radar.tn3270.pairs \
  ioMode=KAFKA kafkaBootstrap=localhost:9092 \
  httpOut=./reports-http tnOut=./reports-tn decode=all

# Poster: forward Kafka pairs to Kafka report topics
java -cp $JAR ca.gc.cra.radar.api.Main poster \
  httpIn=kafka:radar.http.pairs tnIn=kafka:radar.tn3270.pairs \
  ioMode=KAFKA kafkaBootstrap=localhost:9092 \
  posterOutMode=KAFKA \
  kafkaHttpReportsTopic=radar.http.reports \
  kafkaTnReportsTopic=radar.tn3270.reports \
  decode=all
```

Notes:
- `pcapFile=/path/to/trace.pcap` replays packets from disk and ignores `iface=` when provided.
- `protocol=TN3270` selects the default BPF `tcp and (port 23 or port 992)`; override with `bpf="..."` (requires `--enable-bpf`).
- `decode=none|transfer|all` controls how the poster handles `Transfer-Encoding` and `Content-Encoding` headers (`transfer` removes chunked framing; `all` also decompresses gzip/deflate bodies).
- `kafkaBootstrap` is mandatory whenever `ioMode=KAFKA` or `posterOutMode=KAFKA` is used.
- When running capture in FILE mode you need libpcap and appropriate privileges; assemble/poster can operate purely on files.

## Output Layout
```
cap-out/                # raw segment files (*.segbin)
pairs-out/
  http/
    blob-*.seg          # concatenated HTTP headers/bodies
    index-*.ndjson      # per-message metadata
  tn3270/
    blob-tn-*.seg       # binary TN3270 messages
    index-tn-*.ndjson
reports-http/           # poster output (.http)
reports-tn/             # poster output (.tn3270.txt)
```

## Building & Testing
RADAR targets Java 17+ and Maven. Run `mvn verify` for a full build; `mvn -q -DskipTests package` is sufficient for the CLI smoke sequence above. Unit tests cover flow reassembly, protocol adapters, persistence sinks, and poster pipelines.

## Developer Docs
- Generated API reference: `target/site/apidocs/index.html` (run `mvn -DskipTests javadoc:javadoc`).
- Architecture and extension guide: [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md).

## Next Steps
- Extend `ReorderingFlowAssembler` or add adapters for new protocols by wiring a reconstructor and pairing engine into `CompositionRoot`.
- Implement additional `PersistencePort`/poster adapters if you need alternate storage or reporting formats.
- Review the test suite under `src/test/java` for examples of stubbing packet sources, Kafka adapters, and poster outputs.
