package ca.gc.cra.radar.application.port.poster;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import org.junit.jupiter.api.Test;

class PosterReportTest {

  @Test
  void rejectsNullProtocol() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> new PosterOutputPort.PosterReport(null, "tx", 1L, "content"));
    assertEquals("protocol must not be null", ex.getMessage());
  }

  @Test
  void rejectsBlankTransactionId() {
    assertThrows(IllegalArgumentException.class,
        () -> new PosterOutputPort.PosterReport(ProtocolId.HTTP, "  ", 1L, "content"));
  }

  @Test
  void rejectsNullTransactionId() {
    assertThrows(IllegalArgumentException.class,
        () -> new PosterOutputPort.PosterReport(ProtocolId.HTTP, null, 1L, "content"));
  }

  @Test
  void rejectsNullContent() {
    assertThrows(IllegalArgumentException.class,
        () -> new PosterOutputPort.PosterReport(ProtocolId.HTTP, "tx", 1L, null));
  }

  @Test
  void acceptsValidInputsIncludingEmptyContent() {
    PosterOutputPort.PosterReport report =
        new PosterOutputPort.PosterReport(ProtocolId.HTTP, "tx", 123L, "");
    assertEquals(ProtocolId.HTTP, report.protocol());
    assertEquals("tx", report.txId());
    assertEquals(123L, report.timestampMicros());
    assertEquals("", report.content());
  }
}
