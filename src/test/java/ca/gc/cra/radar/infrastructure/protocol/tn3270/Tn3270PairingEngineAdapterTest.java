package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Tn3270PairingEngineAdapterTest {
  private static final FiveTuple FLOW = new FiveTuple("10.0.0.1", 23, "10.0.0.2", 1023, "TCP");

  @Test
  void hostFirstResponsePairsWithNextRequest() throws Exception {
    try (Tn3270PairingEngineAdapter pairing = new Tn3270PairingEngineAdapter()) {
      MessageEvent response = responseEvent("host update", 1);
      MessageEvent request = requestEvent("terminal ack", 2);

      assertTrue(pairing.accept(response).isEmpty(), "response should queue until request arrives");

      Optional<MessagePair> pair = pairing.accept(request);
      assertTrue(pair.isPresent(), "request should flush queued response");
      assertEquals(request, pair.get().request());
      assertEquals(response, pair.get().response());
    }
  }

  @Test
  void queuesMultipleResponsesInOrder() throws Exception {
    try (Tn3270PairingEngineAdapter pairing = new Tn3270PairingEngineAdapter()) {
      MessageEvent rsp1 = responseEvent("screen 1", 10);
      MessageEvent rsp2 = responseEvent("screen 2", 11);
      MessageEvent req1 = requestEvent("input 1", 12);
      MessageEvent req2 = requestEvent("input 2", 13);

      pairing.accept(rsp1);
      pairing.accept(rsp2);

      Optional<MessagePair> first = pairing.accept(req1);
      Optional<MessagePair> second = pairing.accept(req2);

      assertTrue(first.isPresent());
      assertEquals(req1, first.get().request());
      assertEquals(rsp1, first.get().response());

      assertTrue(second.isPresent());
      assertEquals(req2, second.get().request());
      assertEquals(rsp2, second.get().response());
    }
  }

  private static MessageEvent requestEvent(String text, long ts) {
    return new MessageEvent(
        ProtocolId.TN3270,
        MessageType.REQUEST,
        new ByteStream(FLOW, true, text.getBytes(StandardCharsets.US_ASCII), ts),
        new MessageMetadata("txn-" + ts, Map.of()));
  }

  private static MessageEvent responseEvent(String text, long ts) {
    return new MessageEvent(
        ProtocolId.TN3270,
        MessageType.RESPONSE,
        new ByteStream(FLOW, false, text.getBytes(StandardCharsets.US_ASCII), ts),
        new MessageMetadata("txn-" + ts, Map.of()));
  }
}
