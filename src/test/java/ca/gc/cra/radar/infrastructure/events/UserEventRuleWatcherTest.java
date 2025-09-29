package ca.gc.cra.radar.infrastructure.events;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.events.rules.CompiledRuleSet;
import ca.gc.cra.radar.application.events.rules.UserEventRuleEngine;
import ca.gc.cra.radar.application.events.rules.UserEventRuleSetProvider;
import ca.gc.cra.radar.application.events.rules.RuleMatchResult;
import ca.gc.cra.radar.application.events.http.HttpExchangeBuilder;
import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class UserEventRuleWatcherTest {

  @Test
  void reloadsWhenFileChanges() throws Exception {
    Path source = Path.of("src/test/resources/user-events/login-success.yaml");
    Path temp = Files.createTempFile("rules", ".yaml");
    Files.copy(source, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    UserEventRuleSetProvider provider = new UserEventRuleSetProvider();
    CompiledRuleSet ruleSet = provider.load(List.of(temp));
    UserEventRuleEngine engine = new UserEventRuleEngine(ruleSet);
    UserEventRuleWatcher watcher = new UserEventRuleWatcher(List.of(temp), provider, engine, LoggerFactory.getLogger(UserEventRuleWatcherTest.class), 1);

    List<RuleMatchResult> matches = engine.evaluate(HttpExchangeBuilder.from(buildPair()).orElseThrow());
    assertEquals("login_success", matches.get(0).ruleId());

    Thread.sleep(5);
    String modified = Files.readString(temp, StandardCharsets.UTF_8).replace("login_success", "login_success_v2");
    Files.writeString(temp, modified, StandardCharsets.UTF_8);

    watcher.maybeReload();

    List<RuleMatchResult> updated = engine.evaluate(HttpExchangeBuilder.from(buildPair()).orElseThrow());
    assertEquals("login_success_v2", updated.get(0).ruleId());
  }

  private MessagePair buildPair() {
    FiveTuple flow = new FiveTuple("10.0.0.1", 40000, "198.51.100.10", 443, "TCP");
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
