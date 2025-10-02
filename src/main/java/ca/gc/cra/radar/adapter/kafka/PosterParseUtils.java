package ca.gc.cra.radar.adapter.kafka;

import org.slf4j.Logger;

final class PosterParseUtils {
  private PosterParseUtils() {}

  static <T> T logParseFailure(Logger log, String pipeline, RuntimeException ex) {
    log.warn("Failed to parse {} Kafka record", pipeline, ex);
    return null;
  }
}
