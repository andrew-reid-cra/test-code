package ca.gc.cra.radar.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.poster.FilePosterOutputAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class Tn3270KafkaPosterPipelineTest {

  @Test
  void writesHexDumpToFile() throws Exception {
    Path outputDir = Files.createTempDirectory("tn-poster");
    MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    Tn3270KafkaPosterPipeline pipeline = new Tn3270KafkaPosterPipeline("localhost:9092", () -> consumer);

    String topic = "radar.tn3270.pairs";
    TopicPartition partition = new TopicPartition(topic, 0);
    consumer.subscribe(List.of(topic));
    consumer.rebalance(List.of(partition));
    consumer.updateBeginningOffsets(Map.of(partition, 0L));

    String json = "{" +
        "\"protocol\":\"TN3270\"," +
        "\"txId\":\"txn-tn\"," +
        "\"startTs\":1," +
        "\"endTs\":2," +
        "\"client\":{\"ip\":\"10.0.0.1\",\"port\":992}," +
        "\"server\":{\"ip\":\"10.0.0.2\",\"port\":23}," +
        "\"request\":{\"timestamp\":1,\"length\":2,\"payloadB64\":\"fw==\",\"attributes\":{}}," +
        "\"response\":{\"timestamp\":2,\"length\":3,\"payloadB64\":\"8fI=\",\"attributes\":{}}" +
        "}";
    consumer.addRecord(new ConsumerRecord<>(topic, 0, 0L, "txn-tn", json));

    PosterConfig.ProtocolConfig cfg = new PosterConfig.ProtocolConfig(
        Optional.empty(),
        Optional.of(topic),
        Optional.of(outputDir),
        Optional.empty());
    PosterOutputPort output = new FilePosterOutputAdapter(outputDir, ProtocolId.TN3270);

    pipeline.process(cfg, PosterConfig.DecodeMode.NONE, output);

    List<Path> outputs = Files.list(outputDir).filter(Files::isRegularFile).toList();
    assertFalse(outputs.isEmpty(), "expected poster output file");
    String content = Files.readString(outputs.get(0), StandardCharsets.UTF_8);
    assertTrue(content.contains("TN3270 REQUEST"));
    assertTrue(content.contains("7f"), () -> "expected hex dump to include request payload: " + content);
    assertTrue(content.contains("f1 f2"), () -> "expected hex dump to include response payload: " + content);
  }
}
