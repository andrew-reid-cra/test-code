package ca.gc.cra.radar.application.events.http;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpExchangeBuilderTest {

  @Test
  void parsesRequestAndResponse() {
    MessagePair pair = buildPair();
    HttpExchangeContext context = HttpExchangeBuilder.from(pair).orElseThrow();
    assertEquals("POST", context.request().method());
    assertEquals("/submit", context.request().path());
    assertEquals("HTTP/2", context.response().httpVersion());
    assertEquals(201, context.response().status());
    assertEquals("JsonApp", context.request().header("user-agent").orElse(null));
    assertEquals("abc123", context.response().headerValues("set-cookie").get(0).split("=",2)[1].split(";",2)[0]);
    assertEquals("value", context.request().query().get("q"));
  }

  @Test
  void returnsEmptyWhenMalformed() {
    FiveTuple flow = new FiveTuple("1.1.1.1", 1111, "2.2.2.2", 80, "TCP");
    ByteStream stream = new ByteStream(flow, true, "BADDATA".getBytes(StandardCharsets.UTF_8), 0L);
    MessageEvent request = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, stream, null);
    MessagePair pair = new MessagePair(request, null);
    assertTrue(HttpExchangeBuilder.from(pair).isEmpty());
  }

  private MessagePair buildPair() {
    FiveTuple flow = new FiveTuple("192.0.2.10", 55000, "198.51.100.20", 443, "TCP");
    byte[] request = ("POST /submit?q=value HTTP/1.1\r\n"
        + "Host: example.test\r\n"
        + "User-Agent: JsonApp\r\n"
        + "Content-Type: application/json; charset=utf-8\r\n\r\n"
        + "{}")
        .getBytes(StandardCharsets.UTF_8);
    ByteStream requestStream = new ByteStream(flow, true, request, 1_000L);
    MessageEvent requestEvent = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, requestStream, null);

    byte[] response = ("HTTP/2 201 Created\r\n"
        + "Content-Type: application/json\r\n"
        + "Set-Cookie: SID=abc123; Path=/\r\n\r\n"
        + "{}")
        .getBytes(StandardCharsets.UTF_8);
    ByteStream responseStream = new ByteStream(flow, false, response, 2_000L);
    MessageEvent responseEvent = new MessageEvent(ProtocolId.HTTP, MessageType.RESPONSE, responseStream, null);
    return new MessagePair(requestEvent, responseEvent);
  }
}
