package ca.gc.cra.radar.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.junit.jupiter.api.Test;

class HttpKafkaPersistenceAdapterTest {

  @Test
  void serializesMessagePairToKafka() throws Exception {
    MockProducer<String, byte[]> producer = MockProducerFactory.byteArrayProducer();
    HttpKafkaPersistenceAdapter adapter = new HttpKafkaPersistenceAdapter(producer, "radar.http.pairs", null);

    MessagePair pair = httpPair();
    adapter.persist(pair);

    assertEquals(1, producer.history().size());
    var record = producer.history().get(0);
    assertEquals("radar.http.pairs", record.topic());
    String json = new String(record.value(), StandardCharsets.UTF_8);
    assertTrue(json.contains("\"protocol\":\"HTTP\""));
    assertTrue(json.contains("\"txId\":\"txn-http\""));
    assertTrue(json.contains("\"request\""));
    assertTrue(json.contains("\"response\""));
  }

  private static MessagePair httpPair() {
    FiveTuple flow = new FiveTuple("10.1.1.1", 1234, "10.1.1.2", 80, "TCP");
    byte[] requestBytes = "GET /demo HTTP/1.1\r\nHost: example\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    byte[] responseBytes = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.ISO_8859_1);
    MessageEvent request = new MessageEvent(
        ProtocolId.HTTP,
        MessageType.REQUEST,
        new ByteStream(flow, true, requestBytes, 10L),
        new MessageMetadata("txn-http", Map.of()));
    MessageEvent response = new MessageEvent(
        ProtocolId.HTTP,
        MessageType.RESPONSE,
        new ByteStream(flow, false, responseBytes, 20L),
        new MessageMetadata("txn-http", Map.of()));
    return new MessagePair(request, response);
  }
}
