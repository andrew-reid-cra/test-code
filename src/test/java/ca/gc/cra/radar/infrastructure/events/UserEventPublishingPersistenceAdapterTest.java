package ca.gc.cra.radar.infrastructure.events;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.events.SessionResolver;
import ca.gc.cra.radar.application.events.rules.CompiledRuleSet;
import ca.gc.cra.radar.application.events.rules.UserEventRuleEngine;
import ca.gc.cra.radar.application.events.rules.UserEventRuleSetProvider;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.events.UserEvent;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class UserEventPublishingPersistenceAdapterTest {

  @Test
  void emitsUserEventAndDelegates() throws Exception {
    Path rules = Path.of("src/test/resources/user-events/login-success.yaml");
    UserEventRuleSetProvider provider = new UserEventRuleSetProvider();
    CompiledRuleSet ruleSet = provider.load(List.of(rules));
    UserEventRuleEngine engine = new UserEventRuleEngine(ruleSet);
    UserEventRuleWatcher watcher = new UserEventRuleWatcher(List.of(rules), provider, engine, LoggerFactory.getLogger(UserEventPublishingPersistenceAdapterTest.class), 10);

    InMemoryUserEventEmitter emitter = new InMemoryUserEventEmitter();
    TestMetrics metrics = new TestMetrics();
    AtomicBoolean delegated = new AtomicBoolean();
    PersistencePort delegate = pair -> delegated.set(true);

    UserEventPublishingPersistenceAdapter adapter = new UserEventPublishingPersistenceAdapter(
        delegate,
        engine,
        new SessionResolver(),
        emitter,
        watcher,
        metrics,
        "testUserEvents");

    adapter.persist(buildPair());

    List<UserEvent> events = emitter.snapshot();
    assertEquals(1, events.size());
    UserEvent event = events.get(0);
    assertEquals("login_success", event.ruleId());
    assertEquals("user.login", event.eventType());
    assertEquals("u-123", event.userId());
    assertEquals("abc123", event.sessionId());
    assertEquals("Chrome", event.attributes().get("user_agent"));
    assertTrue(delegated.get());
    assertTrue(metrics.metrics.containsKey("testUserEvents.emitted"));
  }

  private MessagePair buildPair() {
    FiveTuple flow = new FiveTuple("10.10.0.5", 50000, "198.51.100.10", 443, "TCP");
    byte[] request = ("POST /api/auth/login?client=web HTTP/1.1\r\n"
        + "Host: example.test\r\n"
        + "User-Agent: Chrome\r\n"
        + "Content-Type: application/json\r\n\r\n"
        + "{\"username\":\"demo\",\"password\":\"secret\"}")
        .getBytes(StandardCharsets.UTF_8);
    ByteStream requestStream = new ByteStream(flow, true, request, 1_000L);
    MessageEvent requestEvent = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, requestStream, null);

    byte[] response = ("HTTP/1.1 200 OK\r\n"
        + "Content-Type: application/json\r\n"
        + "Set-Cookie: JSESSIONID=abc123; Path=/\r\n\r\n"
        + "{\"result\":\"success\",\"user\":{\"id\":\"u-123\"}}")
        .getBytes(StandardCharsets.UTF_8);
    ByteStream responseStream = new ByteStream(flow, false, response, 2_000L);
    MessageEvent responseEvent = new MessageEvent(ProtocolId.HTTP, MessageType.RESPONSE, responseStream, null);
    return new MessagePair(requestEvent, responseEvent);
  }

  private static final class TestMetrics implements MetricsPort {
    private final Map<String, Long> metrics = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void increment(String key) {
      metrics.merge(key, 1L, Long::sum);
    }

    @Override
    public void observe(String key, long value) {
      metrics.put(key, value);
    }
  }
}
