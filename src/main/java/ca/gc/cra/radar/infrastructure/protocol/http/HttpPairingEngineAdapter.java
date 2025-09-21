package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.util.MessagePairer;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import java.util.Objects;
import java.util.Optional;

/**
 * Simple FIFO pairer assuming client -> request and server -> response semantics.
 * <p>Not thread-safe; maintain a dedicated instance per flow.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class HttpPairingEngineAdapter implements PairingEngine, AutoCloseable {
  private final MessagePairer delegate = new MessagePairer();

  /**
   * Creates an HTTP pairing engine adapter with FIFO semantics.
   *
   * @since RADAR 0.1-doc
   */
  public HttpPairingEngineAdapter() {}

  /**
   * Pairs HTTP message events into request/response tuples.
   *
   * @param event message event to pair
   * @return completed pair when available; otherwise empty
   * @throws NullPointerException if {@code event} is {@code null}
   * @throws Exception if pairing fails
   * @implNote Uses {@link MessagePairer} FIFO buffering; responses without prior requests generate best-effort pairs.
   * @since RADAR 0.1-doc
   */
  @Override
  public Optional<MessagePair> accept(MessageEvent event) throws Exception {
    Objects.requireNonNull(event, "event");
    boolean isRequest = event.type() == MessageType.REQUEST;
    return delegate.accept(event, isRequest);
  }

  /**
   * Releases pairing resources by clearing queued events.
   *
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() {
    delegate.close();
  }
}



