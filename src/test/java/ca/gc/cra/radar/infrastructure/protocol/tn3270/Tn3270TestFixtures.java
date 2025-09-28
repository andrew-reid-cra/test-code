package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

public final class Tn3270TestFixtures {
  private static final Charset EBCDIC = Charset.forName("Cp1047");

  private Tn3270TestFixtures() {}

  public static byte[] hostScreenRecord() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xF5); // Erase/Write
    out.write(0x40); // WCC restore keyboard
    // Label: SIN:
    out.write(0x11);
    writeAddress(out, 0);
    out.write(0x1D);
    out.write(0x20); // protected field attribute
    writeEbc(out, "SIN:");
    // Input field placeholder (9 chars)
    out.write(0x11);
    writeAddress(out, 5);
    out.write(0x1D);
    out.write(0x00); // unprotected
    writeSpaces(out, 9);
    // Label: YEAR:
    out.write(0x11);
    writeAddress(out, 20);
    out.write(0x1D);
    out.write(0x20);
    writeEbc(out, "YEAR:");
    // Input field placeholder (4 chars)
    out.write(0x11);
    writeAddress(out, 26);
    out.write(0x1D);
    out.write(0x00);
    writeSpaces(out, 4);
    return out.toByteArray();
  }

  public static byte[] clientSubmitRecord() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x7D); // ENTER AID
    writeAddress(out, 0); // cursor at top-left
    out.write(0x11);
    writeAddress(out, 6);
    writeEbc(out, "123456789");
    out.write(0x11);
    writeAddress(out, 27);
    writeEbc(out, "2024");
    return out.toByteArray();
  }

  public static MessagePair messagePair() {
    return messagePair(hostScreenRecord(), clientSubmitRecord(), 1_000_000L, 1_000_500L);
  }

  public static MessagePair messagePair(byte[] host, byte[] client, long responseTs, long requestTs) {
    FiveTuple flow = flow();
    MessageEvent response =
        new MessageEvent(
            ProtocolId.TN3270,
            MessageType.RESPONSE,
            new ByteStream(flow, false, host, responseTs),
            MessageMetadata.empty());
    MessageEvent request =
        new MessageEvent(
            ProtocolId.TN3270,
            MessageType.REQUEST,
            new ByteStream(flow, true, client, requestTs),
            MessageMetadata.empty());
    return new MessagePair(request, response);
  }

  public static FiveTuple flow() {
    return new FiveTuple("10.0.0.1", 50000, "192.168.0.100", 23, "TCP");
  }

  private static void writeAddress(ByteArrayOutputStream out, int address) {
    int high = ((address >> 6) & 0x3F) | 0xC0;
    int low = (address & 0x3F) | 0xC0;
    out.write(high);
    out.write(low);
  }

  private static void writeSpaces(ByteArrayOutputStream out, int count) {
    for (int i = 0; i < count; i++) {
      out.write(0x40);
    }
  }

  private static void writeEbc(ByteArrayOutputStream out, String text) {
    byte[] bytes = text.getBytes(EBCDIC);
    out.write(bytes, 0, bytes.length);
  }
}


