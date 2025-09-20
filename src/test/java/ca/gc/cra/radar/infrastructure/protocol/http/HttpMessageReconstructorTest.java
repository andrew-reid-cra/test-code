package ca.gc.cra.radar.infrastructure.protocol.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpMessageReconstructorTest {
  @Test
  void reassemblesRequestWithContentLength() {
    MessageReconstructor recon = new HttpMessageReconstructor(ClockPort.SYSTEM, MetricsPort.NO_OP);
    recon.onStart();
    FiveTuple flow = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");

    String headers = "POST /items HTTP/1.1\r\nHost: example\r\nContent-Length: 3\r\n\r\n";
    String body = "foo";

    ByteStream slice = new ByteStream(flow, true, headers.getBytes(StandardCharsets.US_ASCII), 10L);
    List<MessageEvent> first = recon.onBytes(slice);
    assertTrue(first.isEmpty());

    ByteStream slice2 = new ByteStream(flow, true, body.getBytes(StandardCharsets.US_ASCII), 11L);
    List<MessageEvent> second = recon.onBytes(slice2);
    assertEquals(1, second.size());
    MessageEvent event = second.get(0);
    assertEquals(MessageType.REQUEST, event.type());
    assertEquals(headers + body, new String(event.payload().data(), StandardCharsets.US_ASCII));
    assertEquals(11L, event.payload().timestampMicros());
    assertNotNull(event.metadata().transactionId());
  }

  @Test
  void emitsResponseHeadersWhenNoBodyLength() {
    MessageReconstructor recon = new HttpMessageReconstructor(ClockPort.SYSTEM, MetricsPort.NO_OP);
    recon.onStart();
    FiveTuple flow = new FiveTuple("10.0.0.2", 80, "10.0.0.1", 1234, "TCP");

    String response = "HTTP/1.1 204 No Content\r\nDate: now\r\n\r\n";
    ByteStream slice = new ByteStream(flow, false, response.getBytes(StandardCharsets.US_ASCII), 20L);
    List<MessageEvent> events = recon.onBytes(slice);
    assertEquals(1, events.size());
    MessageEvent event = events.get(0);
    assertEquals(MessageType.RESPONSE, event.type());
    assertEquals(response, new String(event.payload().data(), StandardCharsets.US_ASCII));
  }
}


