Overview
This repository is a Java-based network sniffer and reconstruction pipeline for HTTP traffic and TN3270 terminal sessions. It focuses on high-throughput capture, minimal allocations, and streaming writes to disk so large volumes of traffic can be processed with modest memory.

High-Level Flow
Capture (sniffer.capture, sniffer.app)

Uses libpcap (via a JNR FFI adapter) to grab raw packets from an interface.

Defaults and runtime tuning are loaded from `sniffer/capture/capture.properties`, and can be overridden per-run via CLI key/value overrides or by pointing at an alternate properties file with `config=<path>`.

Filters/decodes minimal TCP data and writes each segment to binary “segbin” files (SegmentIO.Writer) for offline processing, while the live capture loop can simultaneously stream HTTP and TN3270 data into SegmentSink.

CLI entry points: CaptureMain (segment writer) and CaptureRunner (live HTTP + TN3270 streaming).

Assemble (sniffer.assemble, sniffer.domain)

Reads captured segments (SegmentIO.Reader), reassembles TCP flows, and extracts HTTP/1.x messages.

In parallel, detects Telnet/TN3270 exchanges, interprets 3270 buffer updates, and emits request/response screen snapshots.

HttpAssembler handles out-of-order segments, bounded buffering, negative caching for non-HTTP traffic, session ID extraction, and streaming output.

Tn3270Assembler reuses the same SegmentSink to write TN3270 frames with meta kinds `TN3270_REQ` / `TN3270_RSP`.

Messages are written to a rotating blob + NDJSON index via SegmentSink (implements HttpStreamSink).

Poster (sniffer.poster)

Pairs HTTP request/response parts using transaction IDs from the index, decompresses/decodes bodies, and emits per-transaction .http files.

Manages many open blob files with an LRU (BlobStore) and supports concurrent workers.

Utility routines (Streams, Util) handle tolerant JSON parsing, chunked transfer decoding, and optional gzip/deflate/brotli decompression.

Tools (sniffer.tools)

Small utilities like SegbinGrep for searching raw capture files.

Docs & Design Notes (sniffer/docs)

Architectural ideas, pipeline planning, and notes on file formats and future steps.

Configuration Defaults

`sniffer/capture/capture.properties` holds the capture defaults (interface, buffer sizes, output directories, rolling policy, etc.).

Each entry is documented inline, and you can:

- Override values on the command line (e.g. `iface=ens2d1`).
- Provide an alternate properties file with `config=/path/to/custom.properties`.

Package Tour
Package	Purpose
sniffer.adapters.libpcap	Thin JNR layer around libpcap (JnrPcapAdapter, FFI structs).
sniffer.capture	Low-level capture loop, TCP decoder, CLI config loaded from properties.
sniffer.pipe	SegmentRecord model and reader/writer for the append-only segment format.
sniffer.domain	Core logic: TCP reassembly (HttpAssembler), TN3270 handling (Tn3270Assembler), session ID extraction, streaming sinks (SegmentSink), and convenience classes.
sniffer.assemble	Main for JVM #2, feeding HttpAssembler + Tn3270Assembler from SegmentIO files.
sniffer.poster	JVM #3 to pair, decode, and emit human-readable HTTP transactions.
sniffer.tools	Miscellaneous command-line helpers.
sniffer.common	Byte-level utilities.
sniffer.app	Simple CLI wrapper around CaptureLoop for quick testing / live streaming of HTTP + TN3270.
Important Concepts
Segment Files (*.segbin) — fixed header format with length-prefixed records of TCP segments; rotated by size/time.

Streaming Output — SegmentSink writes headers and bodies directly to large blob files and tracks offsets in an NDJSON index, allowing random access without many small files.

Session Identification — SessionIdExtractor pulls user session tokens from cookies or headers for later correlation.

HTTP Reassembly Heuristics — Direction is inferred by first-seen packets or SYNs; negative cache avoids repeatedly parsing non-HTTP flows.

TN3270 Screen Tracking — Telnet records are decoded, 3270 buffers are applied, and full-screen snapshots are written whenever a request or response arrives.

Poster Pairing — Request/response parts share a unique ID; Poster matches them, decompresses bodies, and writes .http artifacts.

Getting Started
Study the CLI examples below to see the full end-to-end pipeline.

Read CaptureMain and CaptureRunner to understand how live capture is configured and how segments are serialized.

Dive into HttpAssembler and Tn3270Assembler for the reassembly algorithms and memory-bound buffering strategy.

Examine SegmentSink and RollingFiles to learn how streaming writes and rotation work.

Explore PosterMain for request/response pairing and decoding logic.

Review docs/ for design rationale and future directions.

Next Learning Steps
libpcap & JNR — Understand the FFI layer if you need to adjust capture behavior or add new platforms.

Java NIO & concurrency — SegmentSink and BlobStore rely on channels, buffers, and worker threads.

HTTP internals — To extend parsing (e.g., HTTP/2 or custom session markers) review RFCs and adapt HttpAssembler.

TN3270 protocol — Familiarize yourself with Telnet negotiations, 3270 orders, and buffer addressing to extend the decoder.

File formats & indexing — For custom analytics, inspect the NDJSON index structure emitted by SegmentSink.

Performance tuning — Experiment with the tunable constants in HttpAssembler/Tn3270Assembler (buffer sizes, eviction thresholds).


# test-code

## Step 0 – Pick your capture defaults

Copy `sniffer/capture/capture.properties` to a writable location (optional) and edit any defaults (interface, BPF, output directories). Every key is commented; only override values that differ from your deployment.

## Step 1 – Capture segments (HTTP + TN3270 ready)

```
rm -rf /data/prot/queue/cap-out
java -Xms256m -Xmx1g -XX:+UseG1GC -cp "lib/*:out" sniffer.capture.CaptureMain \
  config=sniffer/capture/capture.properties \
  iface=ens2d1 \
  bpf="(tcp and net 7.33.161.0/24) or (vlan and tcp and net 7.33.161.0/24)" \
  out=/data/prot/queue/cap-out
```

- `config=` points to the default properties file; change it to use a custom one.
- Only pass overrides for values that differ from the file (bufmb, snap, timeout, base, rollMiB already match the defaults shown).
- TN3270 screen output defaults to `./cap-tn3270`; override with `tnOut=...` or disable via `tnEnabled=false` if you only want the segment queue.

## Optional – Live streaming capture (HTTP + TN3270 screens)

If you want immediate streaming of HTTP lines and TN3270 screen snapshots without the intermediate segment files, run:

```
java -Xms256m -Xmx1g -XX:+UseG1GC -cp "lib/*:out" sniffer.app.CaptureRunner \
  iface=ens2d1 \
  bpf="(tcp and net 7.33.161.0/24)" \
  httpOut=/data/prot/queue/live-http \
  tnOut=/data/prot/queue/live-tn3270
```

- HTTP and TN outputs land in separate queues (`live-http/` and `live-tn3270/`).
- Toggle either stream off with `httpEnabled=false` or `tnEnabled=false` when you only need one protocol.
- HTTP first lines are still printed to stdout for quick inspection.

## Step 1.5 – Spot-check the capture

Use the segment grepper to confirm payloads before assembling:

```
java -cp "lib/*:out" sniffer.tools.SegbinGrep in=/data/prot/queue/cap-out \
  needle=<string to look for - needle in the haystack> ctx=48
```

## Step 2 – Assemble HTTP streams and TN3270 screens

```
rm -rf /data/prot/queue/http-out /data/prot/queue/tn3270-out
java -Xms512m -Xmx8g -XX:+UseG1GC -cp "lib/*:out" sniffer.assemble.AssembleMain \
  -Dsniffer.negCacheLog2=18 \
  -Dsniffer.bodyBufCap=65536 \
  -Dsniffer.evictEvery=65536 \
  -Dsniffer.midstream=true \
  in=/data/prot/queue/cap-out \
  httpOut=/data/prot/queue/http-out \
  tnOut=/data/prot/queue/tn3270-out
```

- Outputs are written to separate queues; disable one side with `httpEnabled=false` or `tnEnabled=false` if you only need the other protocol.
- TN3270 entries are tagged as `TN3270_REQ` or `TN3270_RSP` and contain a UTF-8 screen snapshot for each request/response.

## Step 3 - Pair HTTP and TN3270 outputs

```
rm -rf /data/prot/queue/http-post /data/prot/queue/tn3270-post
java -Xms1g -Xmx8g -XX:+UseG1GC -cp "lib/*:out" sniffer.poster.PosterMain \
  --httpIn /data/prot/queue/http-out \
  --httpOut /data/prot/queue/http-post \
  --tnIn /data/prot/queue/tn3270-out \
  --tnOut /data/prot/queue/tn3270-post \
  --workers 32 \
  --decode all \
  --maxOpenBlobs 256
```

- HTTP results land in `http-post/`; TN3270 screen snapshots land in `tn3270-post/`. Disable either pipeline with `--httpEnabled=false` or `--tnEnabled=false` if you only need one.







