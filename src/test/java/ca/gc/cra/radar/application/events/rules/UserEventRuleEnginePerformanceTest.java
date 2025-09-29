package ca.gc.cra.radar.application.events.rules;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.events.http.HttpExchangeBuilder;
import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserEventRuleEnginePerformanceTest {

  @Test
  void evaluatesWithinReasonableTime() throws Exception {
    Path path = Path.of("src/test/resources/user-events/login-success.yaml");
    UserEventRuleSetProvider provider = new UserEventRuleSetProvider();
    CompiledRuleSet ruleSet = provider.load(List.of(path));
    UserEventRuleEngine engine = new UserEventRuleEngine(ruleSet);
    HttpExchangeContext exchange = HttpExchangeBuilder.from(buildPair()).orElseThrow();

    Instant start = Instant.now();
    for (int i = 0; i < 2_000; i++) {
      engine.evaluate(exchange);
    }
    Duration elapsed = Duration.between(start, Instant.now());
    assertTrue(elapsed.toMillis() < 250, "Rule evaluation too slow: " + elapsed.toMillis() + " ms");
  }

  private MessagePair buildPair() {
    FiveTuple flow = new FiveTuple("203.0.113.5", 55000, "198.51.100.10", 443, "TCP");
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
}
