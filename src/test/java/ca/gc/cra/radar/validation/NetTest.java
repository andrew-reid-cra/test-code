package ca.gc.cra.radar.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NetTest {

  @Test
  void validateHostPortHandlesHostname() {
    assertEquals("example.com:443", Net.validateHostPort("example.com:443"));
  }

  @Test
  void validateHostPortHandlesIpv4() {
    assertEquals("10.0.0.1:80", Net.validateHostPort("10.0.0.1:80"));
  }

  @Test
  void validateHostPortHandlesIpv6() {
    assertEquals("[2001:db8::1]:8443", Net.validateHostPort("[2001:db8::1]:8443"));
  }

  @Test
  void validateHostPortRejectsMissingPort() {
    assertThrows(IllegalArgumentException.class, () -> Net.validateHostPort("localhost"));
  }

  @Test
  void validateHostPortRejectsInvalidPort() {
    assertThrows(IllegalArgumentException.class, () -> Net.validateHostPort("localhost:70000"));
  }

  @Test
  void validateHostPortRejectsIpv6WithoutBrackets() {
    assertThrows(IllegalArgumentException.class, () -> Net.validateHostPort("2001:db8::1:443"));
  }
}
