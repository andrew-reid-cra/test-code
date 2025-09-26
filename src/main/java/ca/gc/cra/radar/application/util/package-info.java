/**
 * Message-oriented session utilities that help assemble and pair protocol events.
 * <p><strong>Role:</strong> Domain support aiding the assemble → sink transition by buffering and pairing messages.</p>
 * <p><strong>Concurrency:</strong> Classes assume single-threaded flow ownership; callers must avoid cross-thread access.</p>
 * <p><strong>Performance:</strong> FIFO queues and minimal allocations sized for per-flow workloads.</p>
 * <p><strong>Metrics:</strong> Utilities themselves emit no metrics; callers record pairing latency under {@code assemble.*}.</p>
 * <p><strong>Security:</strong> Operate on validated message content; no credentials stored beyond flow lifetime.</p>
 */
package ca.gc.cra.radar.application.util;
