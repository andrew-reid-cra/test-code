package ca.gc.cra.radar.infrastructure.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DisabledPacketSourceTest {

  @Test
  void startAlwaysThrowsUnsupportedOperation() {
    DisabledPacketSource source = new DisabledPacketSource();
    assertThrows(UnsupportedOperationException.class, source::start);
  }

  @Test
  void pollAlwaysReturnsEmpty() {
    DisabledPacketSource source = new DisabledPacketSource();
    assertEquals(Optional.empty(), source.poll());
  }

  @Test
  void closeIsIdempotent() throws Exception {
    DisabledPacketSource source = new DisabledPacketSource();
    source.close();
    source.close();
  }
}
