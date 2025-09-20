package ca.gc.cra.radar.infrastructure.persistence;

import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.infrastructure.persistence.legacy.LegacySegmentIO;
import ca.gc.cra.radar.infrastructure.persistence.legacy.SegmentRecordMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class LegacySegmentPersistenceAdapter implements PersistencePort {
  private final LegacySegmentIO.Writer writer;
  private final Map<String, Long> nextSequence = new HashMap<>();

  public LegacySegmentPersistenceAdapter(Path directory) {
    try {
      this.writer = new LegacySegmentIO.Writer(directory, "pairs", 1024);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to initialize legacy segment writer", e);
    }
  }

  @Override
  public synchronized void persist(MessagePair pair) throws Exception {
    if (pair == null) return;
    persistEvent(pair.request());
    persistEvent(pair.response());
  }

  private void persistEvent(MessageEvent event) throws Exception {
    if (event == null) return;
    ByteStream stream = event.payload();
    if (stream == null || stream.data().length == 0) {
      return;
    }
    SegmentRecord record = toSegmentRecord(stream);
    writer.append(SegmentRecordMapper.toLegacy(record));
  }

  private SegmentRecord toSegmentRecord(ByteStream stream) {
    FiveTuple flow = stream.flow();
    boolean fromClient = stream.fromClient();
    String srcIp = fromClient ? flow.srcIp() : flow.dstIp();
    int srcPort = fromClient ? flow.srcPort() : flow.dstPort();
    String dstIp = fromClient ? flow.dstIp() : flow.srcIp();
    int dstPort = fromClient ? flow.dstPort() : flow.srcPort();

    byte[] payload = stream.data();
    long sequence = nextSequence(srcIp, srcPort, dstIp, dstPort, fromClient, payload.length);

    int flags = SegmentRecord.ACK;
    if (payload.length > 0) {
      flags |= SegmentRecord.PSH;
    }

    return new SegmentRecord(
        stream.timestampMicros(),
        srcIp,
        srcPort,
        dstIp,
        dstPort,
        sequence,
        flags,
        payload);
  }

  private long nextSequence(String srcIp, int srcPort, String dstIp, int dstPort, boolean fromClient, int length) {
    String key = srcIp + ":" + srcPort + "->" + dstIp + ":" + dstPort + "#" + (fromClient ? "c" : "s");
    long seq = nextSequence.getOrDefault(key, 0L);
    nextSequence.put(key, seq + Math.max(0, length));
    return seq;
  }

  @Override
  public synchronized void close() throws Exception {
    writer.flush();
    writer.close();
  }
}
