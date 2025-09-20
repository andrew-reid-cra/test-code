package ca.gc.cra.radar.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class SegmentKafkaSinkAdapterTest {

  @Test
  void publishesSegmentAsJsonMessage() {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    SegmentKafkaSinkAdapter adapter = new SegmentKafkaSinkAdapter(producer, "radar.segments");

    SegmentRecord record = new SegmentRecord(
        12345L,
        "10.0.0.1",
        1234,
        "10.0.0.2",
        4321,
        42L,
        SegmentRecord.SYN | SegmentRecord.ACK,
        "hello".getBytes(StandardCharsets.UTF_8));

    adapter.persist(record);
    adapter.flush();

    assertEquals(1, producer.history().size());
    var sent = producer.history().get(0);
    assertEquals("radar.segments", sent.topic());
    String payload = new String(sent.value(), StandardCharsets.UTF_8);
    assertTrue(payload.contains("\"ts\":12345"));
    assertTrue(payload.contains("\"src\":\"10.0.0.1\""));
    assertTrue(payload.contains("\"payload\""));
  }
}
