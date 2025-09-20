package ca.gc.cra.radar.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.poster.FilePosterOutputAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class HttpKafkaPosterPipelineTest {

  @Test
  void writesFormattedReportToFile() throws Exception {
    Path outputDir = Files.createTempDirectory("http-poster");
    MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    HttpKafkaPosterPipeline pipeline = new HttpKafkaPosterPipeline("localhost:9092", () -> consumer);

    String topic = "radar.http.pairs";
    TopicPartition partition = new TopicPartition(topic, 0);
    consumer.subscribe(java.util.List.of(topic));
    consumer.rebalance(java.util.List.of(partition));
    consumer.updateBeginningOffsets(Map.of(partition, 0L));

    String json = "{" +
        "\"protocol\":\"HTTP\"," +
        "\"txId\":\"txn-http\"," +
        "\"startTs\":10," +
        "\"endTs\":20," +
        "\"client\":{\"ip\":\"10.0.0.1\",\"port\":1234}," +
        "\"server\":{\"ip\":\"10.0.0.2\",\"port\":80}," +
        "\"request\":{\"timestamp\":10,\"firstLine\":\"GET /demo HTTP/1.1\",\"headers\":\"Host: example\",\"bodyB64\":\"\",\"attributes\":{}}," +
        "\"response\":{\"timestamp\":20,\"firstLine\":\"HTTP/1.1 200 OK\",\"headers\":\"Content-Length: 5\",\"bodyB64\":\"aGVsbG8=\",\"status\":200,\"attributes\":{}}" +
        "}";
    consumer.addRecord(new ConsumerRecord<>(topic, 0, 0L, "txn-http", json));

    PosterConfig.ProtocolConfig cfg = new PosterConfig.ProtocolConfig(
        Optional.empty(),
        Optional.of(topic),
        Optional.of(outputDir),
        Optional.empty());
    PosterOutputPort output = new FilePosterOutputAdapter(outputDir, ProtocolId.HTTP);

    pipeline.process(cfg, PosterConfig.DecodeMode.NONE, output);

    Files.list(outputDir).findFirst().ifPresent(path -> {
      try {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(content.contains("HTTP REQUEST"));
        assertTrue(content.contains("GET /demo"));
        assertTrue(content.contains("HTTP RESPONSE"));
        assertTrue(content.contains("hello"));
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    });
  }
}
