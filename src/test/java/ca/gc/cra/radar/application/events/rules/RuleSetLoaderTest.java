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
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleSetLoaderTest {

  @Test
  void loadsRulesFromYaml() throws Exception {
    Path path = Path.of("src/test/resources/user-events/login-success.yaml");
    UserEventRuleSetProvider provider = new UserEventRuleSetProvider();
    CompiledRuleSet ruleSet = provider.load(List.of(path));
    assertNotNull(ruleSet);
    assertFalse(ruleSet.isEmpty());
    assertEquals(2, ruleSet.size());
    assertEquals("radar-test", ruleSet.defaults().app());
    assertEquals("http-gateway", ruleSet.defaults().source());

    UserEventRuleEngine engine = new UserEventRuleEngine(ruleSet);
    HttpExchangeContext exchange = HttpExchangeBuilder.from(buildLoginPair()).orElseThrow();
    List<RuleMatchResult> matches = engine.evaluate(exchange);
    assertEquals(1, matches.size());
    RuleMatchResult match = matches.get(0);
    assertEquals("login_success", match.ruleId());
    assertEquals("user.login", match.eventType());
    assertEquals("u-123", match.userId());
    assertEquals("web", match.attributes().get("channel"));
    assertEquals("Chrome", match.attributes().get("user_agent"));
  }

  @Test
  void invalidYamlThrows() {
    Path path = Path.of("src/test/resources/user-events/invalid.yaml");
    UserEventRuleSetProvider provider = new UserEventRuleSetProvider();
    assertThrows(IllegalArgumentException.class, () -> provider.load(List.of(path)));
  }

  private MessagePair buildLoginPair() {
    FiveTuple flow = new FiveTuple("10.0.0.1", 44321, "198.51.100.9", 443, "TCP");
    String requestHeaders = "POST /api/auth/login?client=web HTTP/1.1\r\n"
        + "Host: example.test\r\n"
        + "User-Agent: Chrome\r\n"
        + "Content-Type: application/json\r\n\r\n";
    String requestBody = "{\"username\":\"demo\",\"password\":\"secret\"}";
    byte[] requestBytes = (requestHeaders + requestBody).getBytes(StandardCharsets.UTF_8);
    ByteStream requestStream = new ByteStream(flow, true, requestBytes, 1000L);
    MessageEvent requestEvent = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, requestStream, null);

    String responseHeaders = "HTTP/1.1 200 OK\r\n"
        + "Content-Type: application/json\r\n"
        + "Set-Cookie: JSESSIONID=abc123; Path=/; HttpOnly\r\n\r\n";
    String responseBody = "{\"result\":\"success\",\"user\":{\"id\":\"u-123\"}}";
    byte[] responseBytes = (responseHeaders + responseBody).getBytes(StandardCharsets.UTF_8);
    ByteStream responseStream = new ByteStream(flow, false, responseBytes, 2000L);
    MessageEvent responseEvent = new MessageEvent(ProtocolId.HTTP, MessageType.RESPONSE, responseStream, null);
    return new MessagePair(requestEvent, responseEvent);
  }
}
