package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.net.ByteStream;
import java.util.List;

/**
 * <strong>What:</strong> Domain port that promotes ordered TCP byte streams to higher-level protocol messages.
 * <p><strong>Why:</strong> Connects assemble-stage byte streams with sink/persistence pipelines that operate on discrete messages.</p>
 * <p><strong>Role:</strong> Domain port implemented by protocol-specific adapters (HTTP, TN3270, etc.).</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Initialize per-flow parsing state when a capture session begins.</li>
 *   <li>Translate contiguous bytes into {@link MessageEvent} instances.</li>
 *   <li>Release buffers and emit trailing partial messages on shutdown.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations are typically stateful and require single-threaded access per flow.</p>
 * <p><strong>Performance:</strong> Hot-path parsing; adapters should stream bytes and avoid extra copies.</p>
 * <p><strong>Observability:</strong> Implementations should record parsing latency/size metrics (e.g., {@code assemble.message.bytes}).</p>
 *
 * @implNote RADAR invokes these callbacks in order: {@link #onStart()}, repeated {@link #onBytes(ByteStream)}, then {@link #onClose()}.
 * @since 0.1.0
 */
public interface MessageReconstructor {
  /**
   * Prepares the reconstructor for a new session.
   *
   * <p><strong>Concurrency:</strong> Called once per flow before any {@link #onBytes(ByteStream)} invocation.</p>
   * <p><strong>Performance:</strong> Should allocate only minimal state to keep session startup fast.</p>
   * <p><strong>Observability:</strong> Implementations may emit session-start spans or counters.</p>
   */
  void onStart();

  /**
   * Consumes a contiguous byte slice and emits zero or more protocol message events.
   *
   * @param slice byte stream with flow metadata; must not be {@code null}
   * @return ordered {@link MessageEvent} instances extracted from {@code slice}; may be empty but never {@code null}
   * @throws Exception if parsing fails or the session becomes unrecoverable
   *
   * <p><strong>Concurrency:</strong> Callers serialize invocations per flow.</p>
   * <p><strong>Performance:</strong> Expected amortized O(n) in the slice length; adapters should reuse buffers.</p>
   * <p><strong>Observability:</strong> Implementations generally increment metrics per decoded message and emit parse errors.</p>
   */
  List<MessageEvent> onBytes(ByteStream slice) throws Exception;

  /**
   * Releases resources at the end of the session and emits any buffered messages.
   *
   * <p><strong>Concurrency:</strong> Called once after the final {@link #onBytes(ByteStream)} invocation.</p>
   * <p><strong>Performance:</strong> Should clear buffers quickly; no blocking IO expected.</p>
   * <p><strong>Observability:</strong> Implementations may emit completion metrics (e.g., {@code assemble.session.closed}).</p>
   */
  void onClose();
}

