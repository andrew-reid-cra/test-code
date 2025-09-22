package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.infrastructure.buffer.GrowableBuffer;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.msg.TransactionId;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs TN3270 Telnet records into high-level message events.
 * <p>Handles host-first message ordering and Telnet negotiations. Not thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class Tn3270MessageReconstructor implements MessageReconstructor {
  private final ClockPort clock;
  private final Tn3270Metrics metrics;

  private DirectionState client;
  private DirectionState server;
  private final Deque<String> pendingTransactionIds = new ArrayDeque<>();

  /**
   * Creates a reconstructor bound to the supplied clock and metrics sink.
   *
   * @param clock clock used for timestamping generated events
   * @param metrics metrics sink used to record TN3270 reconstruction statistics
   * @throws NullPointerException if {@code clock} or {@code metrics} is {@code null}
   * @since RADAR 0.1-doc
   */
  public Tn3270MessageReconstructor(ClockPort clock, MetricsPort metrics) {
    this.clock = clock;
    this.metrics = new Tn3270Metrics(metrics);
  }

  /**
   * Initializes per-direction decoders and clears pending transactions.
   *
   * @implNote Resets Telnet decoders for client and server directions.
   * @since RADAR 0.1-doc
   */
  @Override
  public void onStart() {
    client = new DirectionState(true);
    server = new DirectionState(false);
    pendingTransactionIds.clear();
  }

  /**
   * Decodes incoming bytes into Telnet records and produces TN3270 message events.
   *
   * @param slice contiguous TCP bytes with orientation metadata; must not be {@code null}
   * @return ordered list of reconstructed TN3270 messages; may be empty
   * @throws NullPointerException if {@code slice} is {@code null}
   * @implNote Splits Telnet records on IAC EOR boundaries and manages host-first transaction ids.
   * @since RADAR 0.1-doc
   */
  @Override
  public List<MessageEvent> onBytes(ByteStream slice) {
    metrics.onBytes(slice.data().length);
    DirectionState dir = slice.fromClient() ? client : server;

    List<byte[]> records = dir.feed(slice.data(), 0, slice.data().length);
    List<MessageEvent> events = new ArrayList<>(records.size());
    for (byte[] record : records) {
      boolean fromClient = slice.fromClient();
      MessageType type = fromClient ? MessageType.REQUEST : MessageType.RESPONSE;
      String transactionId;
      if (!fromClient) {
        transactionId = TransactionId.newId();
        pendingTransactionIds.addLast(transactionId);
      } else {
        transactionId = pendingTransactionIds.isEmpty()
            ? TransactionId.newId()
            : pendingTransactionIds.removeFirst();
      }
      ByteStream stream = new ByteStream(slice.flow(), fromClient, record, slice.timestampMicros());
      MessageMetadata metadata = new MessageMetadata(transactionId, Map.of());
      events.add(new MessageEvent(ProtocolId.TN3270, type, stream, metadata));
    }
    return events;
  }

  /**
   * Releases buffered state and clears pending transaction ids.
   *
   * @implNote Flushes Telnet decoder buffers to avoid cross-session leakage.
   * @since RADAR 0.1-doc
   */
  @Override
  public void onClose() {
    pendingTransactionIds.clear();
    client.reset();
    server.reset();
  }

  private static final class DirectionState {
    private final boolean fromClient;
    private final TelnetRecordDecoder decoder = new TelnetRecordDecoder();

    DirectionState(boolean fromClient) {
      this.fromClient = fromClient;
    }

    List<byte[]> feed(byte[] data, int off, int len) {
      return decoder.feed(data, off, len);
    }

    void reset() {
      decoder.reset();
    }
  }

  private static final class TelnetRecordDecoder {
    private static final int IAC = 0xFF;
    private static final int SB = 0xFA;
    private static final int SE = 0xF0;
    private static final int EOR = 0xEF;
    private static final int WILL = 0xFB;
    private static final int WONT = 0xFC;
    private static final int DO = 0xFD;
    private static final int DONT = 0xFE;

    private final GrowableBuffer record = new GrowableBuffer(1024);
    private boolean pendingIac = false;
    private boolean inSubneg = false;
    private int awaitingOptionCmd = 0;

    List<byte[]> feed(byte[] data, int off, int len) {
      List<byte[]> out = null;
      for (int i = 0; i < len; i++) {
        int b = data[off + i] & 0xFF;
        if (awaitingOptionCmd != 0) {
          awaitingOptionCmd = 0;
          continue;
        }
        if (!pendingIac) {
          if (b == IAC) {
            pendingIac = true;
            continue;
          }
          if (!inSubneg) {
            record.writeByte((byte) b);
          }
          continue;
        }
        pendingIac = false;
        switch (b) {
          case IAC -> record.writeByte((byte) IAC);
          case SB -> inSubneg = true;
          case SE -> inSubneg = false;
          case WILL, WONT, DO, DONT -> awaitingOptionCmd = b;
          case EOR -> {
            if (record.readableBytes() > 0) {
              if (out == null) out = new ArrayList<>();
              out.add(record.copyAll());
            }
          }
          default -> { /* ignore */ }
        }
      }
      return out == null ? List.of() : out;
    }

    void reset() {
      record.clear();
      pendingIac = false;
      inSubneg = false;
      awaitingOptionCmd = 0;
    }

  }

}









