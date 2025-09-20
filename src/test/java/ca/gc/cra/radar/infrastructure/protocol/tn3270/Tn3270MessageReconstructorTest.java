package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import java.util.List;
import org.junit.jupiter.api.Test;

class Tn3270MessageReconstructorTest {
  @Test
  void decodesServerAndClientRecords() throws Exception {
    MessageReconstructor recon = new Tn3270MessageReconstructor(ClockPort.SYSTEM, MetricsPort.NO_OP);
    recon.onStart();
    FiveTuple flow = new FiveTuple("192.168.0.1", 23, "192.168.0.50", 5000, "TCP");

    byte[] serverRecord = new byte[] {(byte) 0xF5, 0x00, 'H', 'I', (byte) 0xFF, (byte) 0xEF};
    List<MessageEvent> serverEvents = recon.onBytes(new ByteStream(flow, false, serverRecord, 10L));
    assertEquals(1, serverEvents.size());
    MessageEvent response = serverEvents.get(0);
    assertEquals(MessageType.RESPONSE, response.type());
    assertNotNull(response.metadata().transactionId());

    byte[] clientRecord = new byte[] {(byte) 0x88, 'O', 'K', (byte) 0xFF, (byte) 0xEF};
    List<MessageEvent> clientEvents = recon.onBytes(new ByteStream(flow, true, clientRecord, 20L));
    assertEquals(1, clientEvents.size());
    MessageEvent request = clientEvents.get(0);
    assertEquals(MessageType.REQUEST, request.type());
    assertEquals(response.metadata().transactionId(), request.metadata().transactionId());
  }
}
