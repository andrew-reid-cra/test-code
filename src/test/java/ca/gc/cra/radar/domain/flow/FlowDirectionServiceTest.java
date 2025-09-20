package ca.gc.cra.radar.domain.flow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FlowDirectionServiceTest {
  @Test
  void infersClientOnSyn() {
    FlowDirectionService svc = new FlowDirectionService();
    assertTrue(svc.fromClient("1.1.1.1", 1234, "2.2.2.2", 80, true, false));
    assertTrue(svc.fromClient("1.1.1.1", 1234, "2.2.2.2", 80, false, true));
    assertFalse(svc.fromClient("2.2.2.2", 80, "1.1.1.1", 1234, false, true));
  }
}


