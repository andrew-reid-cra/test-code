package ca.gc.cra.radar.capture.pcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.infrastructure.capture.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapHandle;
import ca.gc.cra.radar.testutil.PcapFixtures;
import ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PcapFilePacketSourceTest {
  @TempDir Path tempDir;
  private Path fixture;

  @BeforeEach
  void setUpFixture() {
    fixture = Path.of("src", "test", "resources", "pcap", "http_get.pcap").toAbsolutePath().normalize();
    assertTrue(Files.exists(fixture), "fixture must exist");
  }

  @Test
  void startFailsForMissingFile() {
    Path missing = tempDir.resolve("missing.pcap");
    PacketSource source = new PcapFilePacketSource(missing, null, 65535, () -> failingPcap());

    assertThrows(IllegalArgumentException.class, source::start);
  }

  @Test
  void startFailsForDirectory() {
    PacketSource source = new PcapFilePacketSource(tempDir, null, 65535, () -> failingPcap());

    assertThrows(IllegalArgumentException.class, source::start);
  }

  @Test
  void replaysFramesAndDecodesHttpSegment() throws Exception {
    PcapFilePacketSource source = new PcapFilePacketSource(
        fixture, "tcp port 80", 65535, () -> PcapFixtures.offlineStub(fixture));
    source.start();
    assertFalse(source.isExhausted());

    Optional<RawFrame> maybeFrame = source.poll();
    assertFalse(source.isExhausted());
    assertTrue(maybeFrame.isPresent());

    RawFrame frame = maybeFrame.get();
    FrameDecoderLibpcap decoder = new FrameDecoderLibpcap();
    Optional<TcpSegment> maybeSegment = decoder.decode(frame);
    assertTrue(maybeSegment.isPresent());

    TcpSegment segment = maybeSegment.get();
    String payload = new String(segment.payload(), StandardCharsets.US_ASCII);
    assertTrue(payload.startsWith("GET / HTTP/1.1"));
    assertTrue(segment.ack());
    assertTrue(segment.psh());
    assertEquals(49152, segment.flow().srcPort());

    // Second poll should reach EOF and close the source.
    Optional<RawFrame> eof = source.poll();
    assertTrue(eof.isEmpty());
    assertTrue(source.isExhausted());

    source.close();
  }

  @Test
  void appliesBpfFilter() throws Exception {
    PcapFilePacketSource source = new PcapFilePacketSource(
        fixture, "tcp port 443", 65535, () -> PcapFixtures.offlineStub(fixture));
    source.start();

    Optional<RawFrame> frame = source.poll();
    assertTrue(frame.isEmpty());

    // Subsequent poll after EOF should remain empty.
    Optional<RawFrame> eof = source.poll();
    assertTrue(eof.isEmpty());
    assertTrue(source.isExhausted());

    source.close();
  }

  private Pcap failingPcap() {
    throw new AssertionError("pcap should not be created for invalid start");
  }
}



