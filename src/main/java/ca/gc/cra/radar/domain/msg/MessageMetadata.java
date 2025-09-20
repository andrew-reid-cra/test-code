package ca.gc.cra.radar.domain.msg;

import java.util.Map;

public record MessageMetadata(String transactionId, Map<String, String> attributes) {
  public static MessageMetadata empty() {
    return new MessageMetadata(null, Map.of());
  }
}


