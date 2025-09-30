package ca.gc.cra.radar.adapter.kafka;

import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Test helper for constructing Kafka {@link MockProducer} instances compatible with Kafka 4.x.
 */
final class MockProducerFactory {
  private static final Partitioner NO_OP_PARTITIONER = new Partitioner() {
    @Override
    public void configure(Map<String, ?> configs) {
      // no configuration required for deterministic partitioning.
    }

    @Override
    public int partition(
        String topic,
        Object key,
        byte[] keyBytes,
        Object value,
        byte[] valueBytes,
        Cluster cluster) {
      return 0;
    }

    @Override
    public void close() {
      // nothing to release.
    }
  };

  private MockProducerFactory() {}

  static MockProducer<String, String> stringProducer() {
    return create(new StringSerializer());
  }

  static MockProducer<String, byte[]> byteArrayProducer() {
    return create(new ByteArraySerializer());
  }

  private static <V> MockProducer<String, V> create(Serializer<V> valueSerializer) {
    return new MockProducer<>(true, NO_OP_PARTITIONER, new StringSerializer(), valueSerializer);
  }
}
