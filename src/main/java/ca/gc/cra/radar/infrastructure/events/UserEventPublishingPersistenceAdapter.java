package ca.gc.cra.radar.infrastructure.events;

import ca.gc.cra.radar.application.events.SessionResolver;
import ca.gc.cra.radar.application.events.http.HttpExchangeBuilder;
import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.rules.RuleMatchResult;
import ca.gc.cra.radar.application.events.UserEventAssembler;
import ca.gc.cra.radar.application.events.rules.UserEventRuleEngine;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.UserEventEmitter;
import ca.gc.cra.radar.domain.events.UserEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistence decorator that extracts and publishes user events before delegating to the next sink.
 *
 * @since RADAR 1.1.0
 */
public final class UserEventPublishingPersistenceAdapter implements PersistencePort {
  private static final Logger log = LoggerFactory.getLogger(UserEventPublishingPersistenceAdapter.class);

  private final PersistencePort delegate;
  private final UserEventRuleEngine ruleEngine;
  private final SessionResolver sessionResolver;
  private final UserEventEmitter emitter;
  private final UserEventRuleWatcher ruleWatcher;
  private final MetricsPort metrics;
  private final String metricPrefix;

  /**
   * Creates a persistence decorator that publishes user events.
   *
   * @param delegate downstream persistence port; may be {@code null}
   * @param ruleEngine compiled rule engine; must not be {@code null}
   * @param sessionResolver session resolver; must not be {@code null}
   * @param emitter event emitter; must not be {@code null}
   * @param metrics metrics adapter; falls back to {@link MetricsPort#NO_OP} when {@code null}
   * @param metricPrefix prefix used for emitted metrics (e.g., {@code userEvents})
   */
  public UserEventPublishingPersistenceAdapter(
      PersistencePort delegate,
      UserEventRuleEngine ruleEngine,
      SessionResolver sessionResolver,
      UserEventEmitter emitter,
      UserEventRuleWatcher ruleWatcher,
      MetricsPort metrics,
      String metricPrefix) {
    this.delegate = delegate;
    this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine");
    this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.ruleWatcher = ruleWatcher;
    this.metrics = metrics == null ? MetricsPort.NO_OP : metrics;
    this.metricPrefix = metricPrefix == null || metricPrefix.isBlank() ? "userEvents" : metricPrefix.trim();
  }

  @Override
  public void persist(MessagePair pair) throws Exception {
    metrics.increment(metricPrefix + ".received");
    try {
      publishEvents(pair);
    } catch (Exception ex) {
      metrics.increment(metricPrefix + ".error");
      log.warn("User event publishing failed", ex);
    }
    if (delegate != null) {
      delegate.persist(pair);
    }
  }

  private void publishEvents(MessagePair pair) {
    if (ruleWatcher != null) {
      ruleWatcher.maybeReload();
    }
    if (!ruleEngine.hasRules()) {
      metrics.increment(metricPrefix + ".skipped.disabled");
      return;
    }
    if (pair == null) {
      metrics.increment(metricPrefix + ".skipped.nullPair");
      return;
    }
    ProtocolId protocol = pair.request() != null ? pair.request().protocol() :
        (pair.response() != null ? pair.response().protocol() : ProtocolId.UNKNOWN);
    if (protocol != ProtocolId.HTTP) {
      metrics.increment(metricPrefix + ".skipped.protocol");
      return;
    }
    Optional<HttpExchangeContext> exchangeOpt = HttpExchangeBuilder.from(pair);
    if (exchangeOpt.isEmpty()) {
      metrics.increment(metricPrefix + ".skipped.parse");
      return;
    }
    HttpExchangeContext exchange = exchangeOpt.get();
    metrics.increment(metricPrefix + ".parsed");

    List<RuleMatchResult> matches = ruleEngine.evaluate(exchange);
    if (matches.isEmpty()) {
      metrics.increment(metricPrefix + ".match.none");
      return;
    }

    for (RuleMatchResult match : matches) {
      metrics.increment(metricPrefix + ".match");
      UserEvent event = UserEventAssembler.build(ruleEngine, exchange, match, sessionResolver);
      emitSafely(event);
    }
  }

  private void emitSafely(UserEvent event) {
    try {
      emitter.emit(event);
      metrics.increment(metricPrefix + ".emitted");
    } catch (RuntimeException ex) {
      metrics.increment(metricPrefix + ".emit.error");
      log.warn("User event emitter threw an exception", ex);
    }
  }










}
