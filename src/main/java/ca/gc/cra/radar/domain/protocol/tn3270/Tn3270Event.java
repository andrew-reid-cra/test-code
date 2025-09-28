package ca.gc.cra.radar.domain.protocol.tn3270;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable domain event emitted by the TN3270 assembler.
 *
 * @since RADAR 0.2.0
 */
public final class Tn3270Event {
  private final Tn3270EventType type;
  private final SessionKey session;
  private final long timestamp;
  private final ScreenSnapshot screen;
  private final AidKey aid;
  private final Map<String, String> inputFields;
  private final String screenHash;
  private final String screenName;

  private Tn3270Event(Builder builder) {
    this.type = Objects.requireNonNull(builder.type, "type");
    this.session = Objects.requireNonNull(builder.session, "session");
    this.timestamp = builder.timestamp;
    this.screen = builder.screen;
    this.aid = builder.aid;
    this.inputFields = Map.copyOf(builder.inputFields);
    this.screenHash = builder.screenHash;
    this.screenName = builder.screenName;
  }

  /**
   * @return event type
   */
  public Tn3270EventType type() {
    return type;
  }

  /**
   * @return session identifier associated with the event
   */
  public SessionKey session() {
    return session;
  }

  /**
   * @return event timestamp (epoch micros)
   */
  public long timestamp() {
    return timestamp;
  }

  /**
   * @return optional screen snapshot (present on render events)
   */
  public ScreenSnapshot screen() {
    return screen;
  }

  /**
   * @return optional AID key (present on user submit events)
   */
  public AidKey aid() {
    return aid;
  }

  /**
   * @return immutable map of redacted input field values
   */
  public Map<String, String> inputFields() {
    return inputFields;
  }

  /**
   * @return stable hash representing the rendered screen (protected-field hash)
   */
  public String screenHash() {
    return screenHash;
  }

  /**
   * @return logical screen name if inferred
   */
  public String screenName() {
    return screenName;
  }

  /** Builder for {@link Tn3270Event}. */
  public static final class Builder {
    private final Tn3270EventType type;
    private final SessionKey session;
    private final long timestamp;
    private ScreenSnapshot screen;
    private AidKey aid;
    private Map<String, String> inputFields = Map.of();
    private String screenHash;
    private String screenName;

    private Builder(Tn3270EventType type, SessionKey session, long timestamp) {
      this.type = Objects.requireNonNull(type, "type");
      this.session = Objects.requireNonNull(session, "session");
      this.timestamp = timestamp;
    }

    /**
     * Creates a builder.
     *
     * @param type event type
     * @param session session key associated with the event
     * @param timestamp event timestamp (epoch micros)
     * @return builder instance
     */
    public static Builder create(Tn3270EventType type, SessionKey session, long timestamp) {
      return new Builder(type, session, timestamp);
    }

    /**
     * Sets the screen snapshot.
     *
     * @param screen snapshot
     * @return this builder
     */
    public Builder screen(ScreenSnapshot screen) {
      this.screen = screen;
      return this;
    }

    /**
     * Sets the AID key.
     *
     * @param aid aid key
     * @return this builder
     */
    public Builder aid(AidKey aid) {
      this.aid = aid;
      return this;
    }

    /**
     * Sets the user input field map.
     *
     * @param inputFields field map (will be defensively copied)
     * @return this builder
     */
    public Builder inputFields(Map<String, String> inputFields) {
      this.inputFields = Objects.requireNonNullElse(inputFields, Map.of());
      return this;
    }

    /**
     * Sets the screen hash.
     *
     * @param screenHash hash value (may be {@code null})
     * @return this builder
     */
    public Builder screenHash(String screenHash) {
      this.screenHash = screenHash;
      return this;
    }

    /**
     * Sets the screen name.
     *
     * @param screenName logical screen name (may be {@code null})
     * @return this builder
     */
    public Builder screenName(String screenName) {
      this.screenName = screenName;
      return this;
    }

    /**
     * Builds the immutable event.
     *
     * @return event instance
     */
    public Tn3270Event build() {
      return new Tn3270Event(this);
    }
  }
}
