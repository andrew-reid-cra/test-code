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
  bpf="(tcp and port 80) or (tcp and port 23)"

# Assemble both protocols from files into per-protocol directories
java -cp $JAR ca.gc.cra.radar.api.Main assemble \
  in=./cap-out out=./pairs-out httpEnabled=true tnEnabled=true

# Render human-readable transactions
java -cp $JAR ca.gc.cra.radar.api.Main poster \
  httpIn=./pairs-out/http httpOut=./reports-http \
  tnIn=./pairs-out/tn3270 tnOut=./reports-tn decode=all
```

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
