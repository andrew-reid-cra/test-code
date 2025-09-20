package ca.gc.cra.radar.domain.msg;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TransactionIdTest {
  @Test
  void generates26CharId() {
    String id = TransactionId.newId();
    assertNotNull(id);
    assertEquals(26, id.length());
  }
}


