package ca.gc.cra.radar.capture.pcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.infrastructure.capture.libpcap.Pcap;
import ca.gc.cra.radar.infrastructure.capture.libpcap.PcapHandle;
import ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap;
import ca.gc.cra.radar.testutil.PcapFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PcapFilePacketSourceTest {
  @TempDir Path tempDir;
  private Path fixture;
  private Path tnFixture;

  @BeforeEach
  void setUpFixture() {
    fixture = Path.of("src", "test", "resources", "pcap", "http_get.pcap").toAbsolutePath().normalize();
    tnFixture = Path.of("src", "test", "resources", "pcap", "tn3270_min.pcap").toAbsolutePath().normalize();
    assertTrue(Files.exists(fixture), "fixture must exist");
    assertTrue(Files.exists(tnFixture), "tn fixture must exist");
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
  void tn3270DefaultFilterCapturesTelnetAndTls() throws Exception {
    PcapFilePacketSource source = new PcapFilePacketSource(
        tnFixture, "tcp and (port 23 or port 992)", 65535, () -> PcapFixtures.offlineStub(tnFixture));
    FrameDecoderLibpcap decoder = new FrameDecoderLibpcap();
    List<TcpSegment> segments = new ArrayList<>();

    source.start();
    Optional<RawFrame> maybeFrame;
    while ((maybeFrame = source.poll()).isPresent()) {
      Optional<TcpSegment> decoded = decoder.decode(maybeFrame.get());
      assertTrue(decoded.isPresent(), "expected TCP segment");
      segments.add(decoded.get());
    }
    assertTrue(source.isExhausted());
    source.close();

    long port23 = segments.stream().filter(seg -> seg.flow().dstPort() == 23).count();
    long port992 = segments.stream().filter(seg -> seg.flow().dstPort() == 992).count();

    assertEquals(3, segments.size());
    assertEquals(2, port23);
    assertEquals(1, port992);
  }

  @Test
  void tn3270FilterPort23ExcludesTlsSegments() throws Exception {
    PcapFilePacketSource source = new PcapFilePacketSource(
        tnFixture, "tcp and port 23", 65535, () -> PcapFixtures.offlineStub(tnFixture));
    FrameDecoderLibpcap decoder = new FrameDecoderLibpcap();
    List<TcpSegment> segments = new ArrayList<>();

    source.start();
    Optional<RawFrame> maybeFrame;
    while ((maybeFrame = source.poll()).isPresent()) {
      Optional<TcpSegment> decoded = decoder.decode(maybeFrame.get());
      decoded.ifPresent(segments::add);
    }
    assertTrue(source.isExhausted());
    source.close();

    assertEquals(2, segments.size());
    assertTrue(segments.stream().allMatch(seg -> seg.flow().dstPort() == 23));
  }

  @Test
  void tn3270FilterPort992CapturesTlsSegmentsOnly() throws Exception {
    PcapFilePacketSource source = new PcapFilePacketSource(
        tnFixture, "tcp and port 992", 65535, () -> PcapFixtures.offlineStub(tnFixture));
    FrameDecoderLibpcap decoder = new FrameDecoderLibpcap();
    List<TcpSegment> segments = new ArrayList<>();

    source.start();
    Optional<RawFrame> maybeFrame;
    while ((maybeFrame = source.poll()).isPresent()) {
      Optional<TcpSegment> decoded = decoder.decode(maybeFrame.get());
      decoded.ifPresent(segments::add);
    }
    assertTrue(source.isExhausted());
    source.close();

    assertEquals(1, segments.size());
    assertEquals(992, segments.get(0).flow().dstPort());
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



