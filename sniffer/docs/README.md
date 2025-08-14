 A better pattern is:

Append-only segment files for payload bytes (headers+body), rotated by time/size.

A single NDJSON index stream where each line is one HTTP message (REQ/RSP) carrying:

the same transaction id for the paired request/response,

optional session id,

flow/timestamps, and

the byte‐offsets/lengths of its headers and body inside the segment file.

You grep the NDJSON for id: (or session:) to find everything. When you want the raw HTTP, a tiny extractor tool uses the offsets to pull the exact bytes from the segment file and dump a .http for that one record. This keeps inode count tiny (a few files per hour), writes are sequential (NVMe-friendly), and it still preserves full headers+bodies.

What we can learn from your old sensor.zip
Your prior sensor didn’t create a file per message. It used batched fan-out to sinks (libpcap/Napatech → dispatch → app filters → sinks), keeping capture/decode decoupled from emission. We’ll do exactly that: keep your hot capture/reassembly path unchanged, and add one async writer that appends to big files with atomic rotation. That’s how it sustained 10 G: big rings, early drop, zero-copy parsing, and a tiny number of I/O targets.
