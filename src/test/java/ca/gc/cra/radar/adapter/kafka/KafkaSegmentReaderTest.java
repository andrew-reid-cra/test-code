package ca.gc.cra.radar.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaSegmentReaderTest {

  @Test
  void readsSegmentRecordsFromKafka() {
    String topic = "radar.segments";
    MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    KafkaSegmentReader reader = new KafkaSegmentReader(consumer, topic);

    TopicPartition partition = new TopicPartition(topic, 0);
    consumer.subscribe(java.util.List.of(topic));
    consumer.rebalance(java.util.List.of(partition));
    consumer.updateBeginningOffsets(Map.of(partition, 0L));

    String json = "{" +
        "\"ts\":42," +
        "\"src\":\"10.0.0.1\"," +
        "\"sport\":1234," +
        "\"dst\":\"10.0.0.2\"," +
        "\"dport\":4321," +
        "\"seq\":99," +
        "\"flags\":3," +
        "\"payload\":\"aGVsbG8=\"}";
    consumer.addRecord(new ConsumerRecord<>(topic, 0, 0L, "flow", json.getBytes(StandardCharsets.UTF_8)));

    Optional<SegmentRecord> result = reader.poll(Duration.ofMillis(10));
    assertTrue(result.isPresent());
    SegmentRecord record = result.get();
    assertEquals(42L, record.timestampMicros());
    assertEquals("10.0.0.1", record.srcIp());
    assertEquals("hello", new String(record.payload(), StandardCharsets.UTF_8));
  }
}
