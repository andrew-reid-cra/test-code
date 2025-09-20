package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.config.IoMode;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PosterConfigTest {
 




  @Test
  void infersKafkaModeFromHttpInputPrefix() {
    PosterConfig config = PosterConfig.fromMap(Map.of(
        "httpIn", "kafka:radar.http.pairs",
        "httpOut", "./out/http",
        "kafkaBootstrap", "localhost:9092"));
    assertEquals(IoMode.KAFKA, config.ioMode());
    assertTrue(config.http().orElseThrow().kafkaInputTopic().isPresent());
  }

  @Test
  void requiresBootstrapWhenKafkaOutputSelected() {
    assertThrows(IllegalArgumentException.class, () ->
        PosterConfig.fromMap(Map.of(
            "httpIn", "kafka:radar.http.pairs",
            "posterOutMode", "KAFKA",
            "kafkaHttpReportsTopic", "radar.http.reports")));
  }

  @Test
  void requiresOutputTopicForKafkaPosterMode() {
    assertThrows(IllegalArgumentException.class, () ->
        PosterConfig.fromMap(Map.of(
            "httpIn", "kafka:radar.http.pairs",
            "kafkaBootstrap", "localhost:9092",
            "posterOutMode", "KAFKA")));
  }

  @Test
  void rejectsMissingInputs() {
    assertThrows(IllegalArgumentException.class, () -> PosterConfig.fromMap(Map.of()));
  }
}
