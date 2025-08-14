Overview
This repository is a Java‑based network sniffer and HTTP reconstruction pipeline.
It focuses on high‑throughput capture, minimal allocations, and streaming writes to disk so large volumes of traffic can be processed with modest memory.

High‑Level Flow
Capture (sniffer.capture, sniffer.app)

Uses libpcap (via a JNR FFI adapter) to grab raw packets from an interface.

Filters/decodes minimal TCP data and writes each segment to binary “segbin” files (SegmentIO.Writer).

CLI entry points: CaptureMain and CaptureRunner.

Assemble (sniffer.assemble, sniffer.domain)

Reads captured segments (SegmentIO.Reader), reassembles TCP flows, and extracts HTTP/1.x messages.

HttpAssembler handles out‑of‑order segments, bounded buffering, negative caching for non‑HTTP traffic, session ID extraction, and streaming output.

Messages are written to a rotating blob + NDJSON index via SegmentSink (implements HttpStreamSink).

Poster (sniffer.poster)

Pairs request/response parts using transaction IDs from the index, decompresses/decodes bodies, and emits per‑transaction .http files.

Manages many open blob files with an LRU (BlobStore) and supports concurrent workers.

Utility routines (Streams, Util) handle tolerant JSON parsing, chunked transfer decoding, and optional gzip/deflate/brotli decompression.

Tools (sniffer.tools)

Small utilities like SegbinGrep for searching raw capture files.

Docs & Design Notes (sniffer/docs)

Architectural ideas, pipeline planning, and notes on file formats and future steps.

Package Tour
Package	Purpose
sniffer.adapters.libpcap	Thin JNR layer around libpcap (JnrPcapAdapter, FFI structs).
sniffer.capture	Low‑level capture loop, TCP decoder, CLI config.
sniffer.pipe	SegmentRecord model and reader/writer for the append‑only segment format.
sniffer.domain	Core logic: TCP reassembly (HttpAssembler), session ID extraction, streaming sinks (SegmentSink), and convenience classes.
sniffer.assemble	Main for JVM #2, feeding HttpAssembler from SegmentIO files.
sniffer.poster	JVM #3 to pair, decode, and emit human‑readable HTTP transactions.
sniffer.tools	Miscellaneous command‑line helpers.
sniffer.common	Byte‑level utilities.
sniffer.app	Simple CLI wrapper around CaptureLoop for quick testing.
Important Concepts
Segment Files (*.segbin) – fixed header format with length‑prefixed records of TCP segments; rotated by size/time.

Streaming Output – SegmentSink writes headers and bodies directly to large blob files and tracks offsets in an NDJSON index, allowing random access without many small files.

Session Identification – SessionIdExtractor pulls user session tokens from cookies or headers for later correlation.

HTTP Reassembly Heuristics – Direction is inferred by first‑seen packets or SYNs; negative cache avoids repeatedly parsing non‑HTTP flows.

Poster Pairing – Request/response parts share a unique ID; Poster matches them, decompresses bodies, and writes .http artifacts.

Getting Started
Study the CLI examples in README.md to see the full end‑to‑end pipeline.

Read CaptureMain and CaptureRunner to understand how live capture is configured and how segments are serialized.

Dive into HttpAssembler for the reassembly algorithm and memory‑bound buffering strategy.

Examine SegmentSink and RollingFiles to learn how streaming writes and rotation work.

Explore PosterMain for request/response pairing and decoding logic.

Review docs/ for design rationale and future directions.

Next Learning Steps
libpcap & JNR – Understand the FFI layer if you need to adjust capture behavior or add new platforms.

Java NIO & concurrency – SegmentSink and BlobStore rely on channels, buffers, and worker threads.

HTTP internals – To extend parsing (e.g., HTTP/2 or custom session markers) review RFCs and adapt HttpAssembler.

File formats & indexing – For custom analytics, inspect the NDJSON index structure emitted by SegmentSink.

Performance tuning – Experiment with the tunable constants in HttpAssembler (e.g., buffer sizes, eviction thresholds).


# test-code

#Step 1 capture 

rm -r /data/prot/queue/cap-out
java -Xms256m -Xmx1g -XX:+UseG1GC -cp "lib/*:out" sniffer.capture.CaptureMain \
  iface=ens2d1 bufmb=256 snap=65535 timeout=1 \
  bpf="(tcp and net 7.33.161.0/24) or (vlan and tcp and net 7.33.161.0/24)" \
  out=/data/prot/queue/cap-out base=cap rollMiB=512


# tool to look for a needle in the haystack.  you can confirm you saw something in the capture out before proceeding

java -cp "lib/*:out" sniffer.tools.SegbinGrep in=/data/prot/queue/cap-out \
  needle=<string to look for - needle in the haystack> ctx=48



# step 2 assemble http streams

rm -rf /data/prot/queue/http-out
java -Xms512m -Xmx8g -XX:+UseG1GC -cp "lib/*:out" sniffer.assemble.AssembleMain -Dsniffer.negCacheLog2=18\
       	-Dsniffer.bodyBufCap=65536 -Dsniffer.evictEvery=65536 -Dsniffer.midstream=true in=/data/prot/queue/cap-out out=/data/prot/queue/http-out



# Step 3 - pair request / responses - assign unique ids to pairings - todo add sessioning

rm -rf //data/prot/queue/final-out
 java -Xms1g -Xmx8g -XX:+UseG1GC -cp "lib/*:out" sniffer.poster.PosterMain \
  --in /data/prot/radar/queue/http-out \
  --out /data/prot/queue/final-out \
  --workers 32 \
  --decode all \
  --maxOpenBlobs 256
