Should you split into multiple JVMs?
If you need sustained capture at tens of thousands of concurrent flows with 8 GiB, I’d strongly recommend split pipeline:

Capture JVM

libpcap immediate mode + large kernel ring

Minimal decode (just link-layer/IP/TCP tuples + raw payload pointer)

Append frames to a bounded, disk-backed queue (Chronicle Queue is excellent here: zero-copy, memory-mapped, very low GC).

Rotate files (size- or time-based).

Assembler JVM

Reads from the queue at its own pace (natural backpressure).

Bounded per-flow state, lazy buffers as above.

Streams HTTP to your SegmentSink.

(Optional) Poster JVM

Compress/ship/index outputs, completely off the hot path.

Benefits:

A GC hiccup in one stage doesn’t drop packets.

You control the working set precisely (queue size, per-flow caps).

Crash isolation & simpler tuning (each process has a small, steady heap).

If you want, I can sketch the Chronicle Queue handoff (producer + consumer) as drop-in files for your project next, so you can try the two-JVM layout without changing your higher-level logic.
