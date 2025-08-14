




Notes & next steps
This file format is intentionally simple and fast: length-prefixed records with a tiny file header.

We skip pure ACKs (zero payload, only ACK flag) to keep files small. FIN/PSH/SYN etc. are kept.

Direction is determined by first seen (or SYN without ACK when present). That’s enough for a good offline prototype; you can strengthen heuristics later (e.g., prefer server ports < 49152).

When you’re happy with this, I can give you PosterMain (JVM #3) to compress/ship the SegmentSink outputs—or pivot to a Chronicle Queue when you want live decoupling.

If anything doesn’t compile in your tree, paste the error and I’ll provide a drop-in fix.
