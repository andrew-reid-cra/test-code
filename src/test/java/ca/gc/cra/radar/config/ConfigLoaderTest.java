package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
  @Test
  void loadsDefaultsWhenFileMissing() throws Exception {
    Config cfg = ConfigLoader.fromProperties(null);
    assertEquals("eth0", cfg.interfaceName());
    assertEquals(Set.of(ProtocolId.HTTP, ProtocolId.TN3270), cfg.enabledProtocols());
  }

  @Test
  void loadsProtocolsFromFile() throws Exception {
    Path temp = Files.createTempFile("cfg", ".properties");
    Files.writeString(temp, "protocols=http\n");
    Config cfg = ConfigLoader.fromProperties(temp);
    assertEquals(Set.of(ProtocolId.HTTP), cfg.enabledProtocols());
  }
}


