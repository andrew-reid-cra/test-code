package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PosterConfigTest {

  @Test
  void infersKafkaModeFromHttpInputPrefix() {
    PosterConfig config = PosterConfig.fromMap(Map.of(
        "httpIn", "kafka:radar.http.pairs",
        "httpOut", Path.of("reports", "http").toString(),
        "kafkaBootstrap", "localhost:9092"));

    assertEquals(IoMode.KAFKA, config.ioMode());
    assertTrue(config.http().orElseThrow().kafkaInputTopic().isPresent());
    assertEquals("radar.http.pairs", config.http().orElseThrow().kafkaInputTopic().orElseThrow());
    assertEquals("localhost:9092", config.kafkaBootstrap().orElseThrow());
  }

  @Test
  void infersKafkaModeFromExplicitTopicArguments() {
    PosterConfig config = PosterConfig.fromMap(Map.of(
        "kafkaBootstrap", "localhost:9092",
        "kafkaHttpPairsTopic", "radar.http.pairs",
        "kafkaHttpReportsTopic", "radar.http.reports"));

    assertEquals(IoMode.KAFKA, config.ioMode());
    assertEquals(IoMode.KAFKA, config.posterOutMode());
    assertEquals("radar.http.pairs", config.http().orElseThrow().kafkaInputTopic().orElseThrow());
    assertEquals("radar.http.reports", config.http().orElseThrow().kafkaOutputTopic().orElseThrow());
  }

  @Test
  void requiresBootstrapWhenKafkaOutputSelected() {
    assertThrows(IllegalArgumentException.class, () -> PosterConfig.fromMap(Map.of(
        "httpIn", "kafka:radar.http.pairs",
        "posterOutMode", "KAFKA",
        "kafkaHttpReportsTopic", "radar.http.reports")));
  }

  @Test
  void requiresOutputTopicForKafkaPosterMode() {
    assertThrows(IllegalArgumentException.class, () -> PosterConfig.fromMap(Map.of(
        "httpIn", "kafka:radar.http.pairs",
        "kafkaBootstrap", "localhost:9092",
        "posterOutMode", "KAFKA")));
  }

  @Test
  void requiresAtLeastOneInput() {
    assertThrows(IllegalArgumentException.class, () -> PosterConfig.fromMap(Map.of()));
  }
}
