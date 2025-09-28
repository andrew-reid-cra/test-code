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
    writeBaseScreenOrders(out);
    return out.toByteArray();
  }

  public static byte[] hostStructuredFieldRecord() {
    return structuredFieldWithOrders(0x02, 0, 0x40, 24, 80);
  }

  public static byte[] hostStructuredPartitionRecord(int partitionId, int rows, int cols) {
    return hostStructuredPartitionRecord(partitionId, rows, cols, 0x00);
  }

  public static byte[] hostStructuredPartitionRecord(int partitionId, int rows, int cols, int flags) {
    return structuredFieldWithOrders(0x04, partitionId, flags, rows, cols);
  }

  public static byte[] hostQueryReplyRecord(int partitionId, int rows, int cols) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xF3);
    out.write(0x00);
    int length = 3 + 3; // payload + header bytes
    out.write((length >> 8) & 0xFF);
    out.write(length & 0xFF);
    out.write(0x81); // Query Reply
    out.write(partitionId & 0xFF);
    out.write(rows & 0xFF);
    out.write(cols & 0xFF);
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

  public static MessagePair messagePairWithStructuredField() {
    return messagePair(hostStructuredFieldRecord(), clientSubmitRecord(), 1_000_000L, 1_000_500L);
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

  private static byte[] structuredFieldWithOrders(int sfid, int partitionId, int flags, int rows, int cols) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.write(partitionId & 0xFF);
    payload.write(flags & 0xFF);
    payload.write(rows & 0xFF);
    payload.write(cols & 0xFF);
    ByteArrayOutputStream orders = new ByteArrayOutputStream();
    writeBaseScreenOrders(orders);
    byte[] orderBytes = orders.toByteArray();
    payload.write(orderBytes, 0, orderBytes.length);

    byte[] structuredPayload = payload.toByteArray();
    int length = structuredPayload.length + 3;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xF3);
    out.write(0x00);
    out.write((length >> 8) & 0xFF);
    out.write(length & 0xFF);
    out.write(sfid & 0xFF);
    out.write(structuredPayload, 0, structuredPayload.length);
    return out.toByteArray();
  }

  private static void writeBaseScreenOrders(ByteArrayOutputStream out) {
    out.write(0x11);
    writeAddress(out, 0);
    out.write(0x1D);
    out.write(0x20); // protected field attribute
    writeEbc(out, "SIN:");

    out.write(0x11);
    writeAddress(out, 5);
    out.write(0x1D);
    out.write(0x00); // unprotected
    writeSpaces(out, 9);

    out.write(0x11);
    writeAddress(out, 20);
    out.write(0x1D);
    out.write(0x20);
    writeEbc(out, "YEAR:");

    out.write(0x11);
    writeAddress(out, 26);
    out.write(0x1D);
    out.write(0x00);
    writeSpaces(out, 4);
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
