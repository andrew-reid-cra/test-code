package ca.gc.cra.radar.adapter.kafka;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.ProtocolConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.poster.FilePosterOutputAdapter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class HttpKafkaPosterPipelineTest {

  @Test
  void rejectsBlankBootstrap() {
    assertThrows(IllegalArgumentException.class, () -> new HttpKafkaPosterPipeline("  "));
    assertThrows(NullPointerException.class, () -> new HttpKafkaPosterPipeline(null));
  }

  @Test
  void writesFormattedReportToFile() throws Exception {
    Path outputDir = Files.createTempDirectory("http-poster");
    MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    HttpKafkaPosterPipeline pipeline = new HttpKafkaPosterPipeline("localhost:9092", () -> consumer);

    String topic = "radar.http.pairs";
    primeConsumer(consumer, topic);

    String json = "{" +
        "\"protocol\":\"HTTP\"," +
        "\"txId\":\"txn-http\"," +
        "\"startTs\":10," +
        "\"endTs\":20," +
        "\"client\":{\"ip\":\"10.0.0.1\",\"port\":1234}," +
        "\"server\":{\"ip\":\"10.0.0.2\",\"port\":80}," +
        "\"request\":{\"timestamp\":10,\"firstLine\":\"GET /demo HTTP/1.1\",\"headers\":\"Host: example\",\"bodyB64\":\"\",\"status\":0,\"attributes\":{}}," +
        "\"response\":{\"timestamp\":20,\"firstLine\":\"HTTP/1.1 200 OK\",\"headers\":\"Content-Length: 5\",\"bodyB64\":\"aGVsbG8=\",\"status\":200,\"attributes\":{}}" +
        "}";
    consumer.addRecord(new ConsumerRecord<>(topic, 0, 0L, "txn-http", json));

    ProtocolConfig cfg = new ProtocolConfig(
        Optional.empty(),
        Optional.of(topic),
        Optional.of(outputDir),
        Optional.empty());
    PosterOutputPort output = new FilePosterOutputAdapter(outputDir, ProtocolId.HTTP);

    pipeline.process(cfg, PosterConfig.DecodeMode.NONE, output);

    Path poster = Files.list(outputDir).findFirst().orElseThrow();
    String content = Files.readString(poster, StandardCharsets.UTF_8);
    assertTrue(content.contains("HTTP REQUEST"));
    assertTrue(content.contains("GET /demo"));
    assertTrue(content.contains("HTTP RESPONSE"));
    assertTrue(content.contains("hello"));
  }

  @Test
  void requiresConfigAndOutputPort() {
    HttpKafkaPosterPipeline pipeline = new HttpKafkaPosterPipeline("localhost:9092", () -> new MockConsumer<>(OffsetResetStrategy.EARLIEST));
    ProtocolConfig cfg = new ProtocolConfig(Optional.empty(), Optional.of("topic"), Optional.empty(), Optional.empty());

    assertThrows(NullPointerException.class, () -> pipeline.process(null, PosterConfig.DecodeMode.NONE, report -> {}));
    assertThrows(NullPointerException.class, () -> pipeline.process(cfg, PosterConfig.DecodeMode.NONE, null));
  }

  @Test
  void requiresKafkaInputTopic() {
    HttpKafkaPosterPipeline pipeline = new HttpKafkaPosterPipeline("localhost:9092", () -> new MockConsumer<>(OffsetResetStrategy.EARLIEST));
    ProtocolConfig cfg = new ProtocolConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> pipeline.process(cfg, PosterConfig.DecodeMode.NONE, report -> {}));
  }

  @Test
  void stopsAfterIdlePollsWithoutRecords() throws Exception {
    MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    HttpKafkaPosterPipeline pipeline = new HttpKafkaPosterPipeline("localhost:9092", () -> consumer);
    String topic = "radar.http.empty";
    primeConsumer(consumer, topic);

    ProtocolConfig cfg = new ProtocolConfig(Optional.empty(), Optional.of(topic), Optional.empty(), Optional.empty());
    RecordingPosterOutput output = new RecordingPosterOutput();

    pipeline.process(cfg, PosterConfig.DecodeMode.NONE, output);

    assertTrue(output.reports.isEmpty(), "no output expected when no records are consumed");
  }

  @Test
  void parsesAttributesAndDecodesPayloads() throws Exception {
    MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    HttpKafkaPosterPipeline pipeline = new HttpKafkaPosterPipeline("localhost:9092", () -> consumer);
    String topic = "radar.http.decode";
    primeConsumer(consumer, topic);

    byte[] chunked = TestBytes.ascii("c\r\ndecoded text\r\n0\r\n\r\n");
    byte[] gzipped = TestBytes.gzip(TestBytes.ascii("decoded text"));

    String json = "{" +
        "\"protocol\":\"HTTP\"," +
        "\"txId\":\"txn-decode\"," +
        "\"startTs\":1," +
        "\"endTs\":2," +
        "\"client\":{\"ip\":\"10.0.0.1\",\"port\":1234}," +
        "\"server\":{\"ip\":\"10.0.0.2\",\"port\":80}," +
        "\"request\":{\"timestamp\":1,\"firstLine\":\"GET /demo HTTP/1.1\",\"headers\":\"Transfer-Encoding: chunked\\r\\n\",\"bodyB64\":\"" + Base64.getEncoder().encodeToString(chunked) + "\",\"status\":0,\"attributes\":{\"trace\":\"abc\"}}," +
        "\"response\":{\"timestamp\":2,\"firstLine\":\"HTTP/1.1 200 OK\",\"headers\":\"Content-Encoding: gzip\\r\\n\",\"bodyB64\":\"" + Base64.getEncoder().encodeToString(gzipped) + "\",\"status\":200,\"attributes\":{}}" +
        "}";
    consumer.addRecord(new ConsumerRecord<>(topic, 0, 0L, "txn-decode", json));

    ProtocolConfig cfg = new ProtocolConfig(Optional.empty(), Optional.of(topic), Optional.empty(), Optional.empty());
    RecordingPosterOutput output = new RecordingPosterOutput();

    pipeline.process(cfg, PosterConfig.DecodeMode.ALL, output);

    assertEquals(1, output.reports.size(), "expected single report");
    PosterReport report = output.reports.get(0);
    assertEquals(ProtocolId.HTTP, report.protocol());
    assertTrue(report.content().contains("decoded text"));
    assertFalse(report.content().contains("Transfer-Encoding"));
    assertFalse(report.content().contains("Content-Encoding"));
    assertTrue(report.content().contains("# attrs: trace=abc"));
  }

  private static void primeConsumer(MockConsumer<String, String> consumer, String topic) {
    TopicPartition partition = new TopicPartition(topic, 0);
    consumer.subscribe(List.of(topic));
    consumer.rebalance(List.of(partition));
    consumer.updateBeginningOffsets(Map.of(partition, 0L));
    consumer.updateEndOffsets(Map.of(partition, 0L));
  }

  private static final class RecordingPosterOutput implements PosterOutputPort {
    final java.util.List<PosterReport> reports = new java.util.ArrayList<>();

    @Override
    public void write(PosterReport report) {
      reports.add(report);
    }
  }

  private static final class TestBytes {
    private TestBytes() {}

    static byte[] ascii(String value) {
      return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] gzip(byte[] data) throws Exception {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
        gzip.write(data);
      }
      return buffer.toByteArray();
    }
  }
}


