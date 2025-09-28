package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.protocol.tn3270.AidKey;
import ca.gc.cra.radar.domain.protocol.tn3270.ScreenSnapshot;
import ca.gc.cra.radar.domain.protocol.tn3270.SessionKey;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Event;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270EventType;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Parser;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270SessionState;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;

class Tn3270KafkaPosterTest {

  @Test
  void acceptUserSubmitPublishesEvent() throws Exception {
    RecordingProducer producer = new RecordingProducer();
    Tn3270KafkaPoster poster = new Tn3270KafkaPoster(producer, "user-topic", "render-topic");
    SessionKey session = new SessionKey("10.0.0.1", 10000, "192.168.0.100", 23, null);
    Tn3270Event event =
        Tn3270Event.Builder.create(Tn3270EventType.USER_SUBMIT, session, nowMicros())
            .aid(AidKey.ENTER)
            .inputFields(Map.of("SIN:", "123456789"))
            .screenHash("hash-value")
            .build();

    poster.accept(event);

    assertEquals(1, producer.records.size());
    ProducerRecord<String, byte[]> record = producer.records.get(0);
    assertEquals("user-topic", record.topic());
    String payload = new String(record.value(), StandardCharsets.UTF_8);
    assertTrue(payload.contains("\"type\":\"USER_SUBMIT\""));
    assertTrue(payload.contains("123456789"));
  }

  @Test
  void acceptScreenRenderPublishesEvent() throws Exception {
    RecordingProducer producer = new RecordingProducer();
    Tn3270KafkaPoster poster = new Tn3270KafkaPoster(producer, "user-topic", "render-topic");
    SessionKey session = new SessionKey("10.0.0.1", 10000, "192.168.0.100", 23, null);
    Tn3270SessionState state = new Tn3270SessionState();
    ScreenSnapshot snapshot =
        Tn3270Parser.parseHostWrite(ByteBuffer.wrap(Tn3270TestFixtures.hostScreenRecord()), state);
    Tn3270Event event =
        Tn3270Event.Builder.create(Tn3270EventType.SCREEN_RENDER, session, nowMicros())
            .screen(snapshot)
            .screenHash(state.lastScreenHash())
            .build();

    poster.accept(event);

    assertEquals(1, producer.records.size());
    assertEquals("render-topic", producer.records.get(0).topic());
    String payload = new String(producer.records.get(0).value(), StandardCharsets.UTF_8);
    assertTrue(payload.contains("\"type\":\"SCREEN_RENDER\""));
  }

  @Test
  void acceptHandlesProducerException() {
    RecordingProducer producer = new RecordingProducer();
    producer.failOnSend.set(true);
    Tn3270KafkaPoster poster = new Tn3270KafkaPoster(producer, "user-topic", "render-topic");
    SessionKey session = new SessionKey("10.0.0.1", 10000, "192.168.0.100", 23, null);
    Tn3270Event event =
        Tn3270Event.Builder.create(Tn3270EventType.USER_SUBMIT, session, nowMicros())
            .aid(AidKey.ENTER)
            .inputFields(Map.of("SIN:", "123456789"))
            .build();

    assertDoesNotThrow(() -> poster.accept(event));
    assertTrue(producer.records.isEmpty());
  }

  @Test
  void closeFlushesAndClosesProducer() {
    RecordingProducer producer = new RecordingProducer();
    Tn3270KafkaPoster poster = new Tn3270KafkaPoster(producer, "user-topic", "render-topic");

    poster.close();

    assertTrue(producer.flushed.get());
    assertTrue(producer.closed.get());
  }

  private static long nowMicros() {
    return Instant.now().toEpochMilli() * 1_000;
  }

  private static final class RecordingProducer implements Producer<String, byte[]> {
    final List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
    final AtomicBoolean flushed = new AtomicBoolean();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicBoolean failOnSend = new AtomicBoolean();
    final AtomicInteger closeCalls = new AtomicInteger();

    @Override
    public Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
      return send(record, null);
    }

    @Override
    public Future<RecordMetadata> send(
        ProducerRecord<String, byte[]> record, Callback callback) {
      if (failOnSend.get()) {
        if (callback != null) {
          callback.onCompletion(null, new RuntimeException("send failure"));
        }
        throw new RuntimeException("send failure");
      }
      records.add(record);
      if (callback != null) {
        callback.onCompletion(null, null);
      }
      CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
      future.complete(null);
      return future;
    }

    @Override
    public void flush() {
      flushed.set(true);
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
      return List.of();
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
      return Map.of();
    }

    @Override
    public void close() {
      closed.set(true);
      closeCalls.incrementAndGet();
    }

    @Override
    public void close(Duration timeout) {
      close();
    }

    @Override
    public void initTransactions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void beginTransaction() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendOffsetsToTransaction(
        Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets,
        String consumerGroupId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendOffsetsToTransaction(
        Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets,
        ConsumerGroupMetadata groupMetadata) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void commitTransaction() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void abortTransaction() {
      throw new UnsupportedOperationException();
    }
  }
}

