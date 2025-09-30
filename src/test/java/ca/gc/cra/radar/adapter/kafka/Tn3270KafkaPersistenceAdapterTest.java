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
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.junit.jupiter.api.Test;

class Tn3270KafkaPersistenceAdapterTest {

  @Test
  void serializesBinaryPairToKafka() throws Exception {
    MockProducer<String, byte[]> producer = MockProducerFactory.byteArrayProducer();
    Tn3270KafkaPersistenceAdapter adapter =
        new Tn3270KafkaPersistenceAdapter(producer, "radar.tn3270.pairs", null);

    adapter.persist(tnPair());

    assertEquals(1, producer.history().size());
    var record = producer.history().get(0);
    assertEquals("radar.tn3270.pairs", record.topic());
    String json = new String(record.value(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(json.contains("\"protocol\":\"TN3270\""));
    assertTrue(json.contains("\"payloadB64\""));
  }

  private static MessagePair tnPair() {
    FiveTuple flow = new FiveTuple("10.2.2.1", 992, "10.2.2.2", 23, "TCP");
    byte[] rsp = new byte[] {(byte) 0xF1, (byte) 0xF2, 0x10};
    byte[] req = new byte[] {(byte) 0x7E, (byte) 0xFF};
    MessageEvent response = new MessageEvent(
        ProtocolId.TN3270,
        MessageType.RESPONSE,
        new ByteStream(flow, false, rsp, 30L),
        new MessageMetadata("txn-tn", Map.of()));
    MessageEvent request = new MessageEvent(
        ProtocolId.TN3270,
        MessageType.REQUEST,
        new ByteStream(flow, true, req, 40L),
        new MessageMetadata("txn-tn", Map.of()));
    return new MessagePair(request, response);
  }
}
