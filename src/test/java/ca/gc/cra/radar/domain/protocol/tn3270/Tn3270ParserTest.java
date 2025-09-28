package ca.gc.cra.radar.domain.protocol.tn3270;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270TestFixtures;
import java.nio.ByteBuffer;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Parser.SubmitResult;
import org.junit.jupiter.api.Test;

class Tn3270ParserTest {

  @Test
  void parseHostWriteBuildsSnapshotAndFields() {
    Tn3270SessionState state = new Tn3270SessionState();
    ScreenSnapshot snapshot =
        Tn3270Parser.parseHostWrite(ByteBuffer.wrap(Tn3270TestFixtures.hostScreenRecord()), state);

    assertNotNull(snapshot);
    assertEquals(24, snapshot.rows());
    assertEquals(80, snapshot.cols());
    assertEquals(4, snapshot.fields().size());

    ScreenField labelField = snapshot.fields().get(0);
    assertTrue(labelField.protectedField());
    assertEquals("SIN:", labelField.value().trim());

    ScreenField yearLabel = snapshot.fields().get(2);
    assertEquals("YEAR:", yearLabel.value().trim());

    assertNotNull(state.lastScreenHash());
  }

  @Test
  void parseClientSubmitExtractsModifiedFields() {
    Tn3270SessionState state = new Tn3270SessionState();
    Tn3270Parser.parseHostWrite(ByteBuffer.wrap(Tn3270TestFixtures.hostScreenRecord()), state);

    SubmitResult result =
        Tn3270Parser.parseClientSubmit(ByteBuffer.wrap(Tn3270TestFixtures.clientSubmitRecord()), state);

    assertEquals(AidKey.ENTER, result.aid());
    assertEquals("123456789", result.inputs().get("SIN:"));
    assertEquals("2024", result.inputs().get("YEAR:"));
  }

  @Test
  void parseWriteStructuredFieldReadBufferRendersSnapshot() {
    Tn3270SessionState state = new Tn3270SessionState();
    ScreenSnapshot snapshot =
        Tn3270Parser.parseHostWrite(
            ByteBuffer.wrap(Tn3270TestFixtures.hostStructuredFieldRecord()), state);

    assertNotNull(snapshot);
    assertEquals(24, snapshot.rows());
    assertEquals(80, snapshot.cols());
    assertEquals(4, snapshot.fields().size());
    assertEquals(0x40, state.partitionFlags());
    assertEquals(0, state.partitionAttributes().length);
    assertEquals(0x40, state.partitionSnapshot(0).flags());

    assertEquals("SIN:", snapshot.fields().get(0).value().trim());
    assertEquals("YEAR:", snapshot.fields().get(2).value().trim());
  }

  @Test
  void parseWriteStructuredFieldReadPartitionMaintainsIndependentBuffers() {
    Tn3270SessionState state = new Tn3270SessionState();
    Tn3270Parser.parseHostWrite(
        ByteBuffer.wrap(Tn3270TestFixtures.hostStructuredFieldRecord()), state);

    byte[] partitionPayload =
        Tn3270TestFixtures.hostStructuredPartitionRecord(1, 24, 80, 0x08);
    ScreenSnapshot partitionSnapshot =
        Tn3270Parser.parseHostWrite(ByteBuffer.wrap(partitionPayload), state);

    assertNotNull(partitionSnapshot);
    assertEquals(1, state.activePartitionId());
    assertEquals(0x08, state.partitionFlags());

    Tn3270SessionState.PartitionSnapshot primary = state.partitionSnapshot(0);
    Tn3270SessionState.PartitionSnapshot secondary = state.partitionSnapshot(1);

    assertEquals(4, primary.fields().size());
    assertEquals(4, secondary.fields().size());
    assertEquals(24 * 80, primary.buffer().length);
    assertEquals(24 * 80, secondary.buffer().length);
    assertEquals(0x40, primary.flags());
    assertEquals(0x08, secondary.flags());
    assertEquals(0, secondary.attributes().length);
    assertEquals(0, primary.attributes().length);

    assertEquals(0, primary.fields().get(0).partitionId());
    assertEquals(1, secondary.fields().get(0).partitionId());
    assertEquals((byte) 0x20, secondary.fields().get(0).attribute());
  }

  @Test
  void parseWriteStructuredFieldQueryReplyUpdatesGeometry() {
    Tn3270SessionState state = new Tn3270SessionState();
    Tn3270Parser.parseHostWrite(
        ByteBuffer.wrap(Tn3270TestFixtures.hostStructuredFieldRecord()), state);

    Tn3270Parser.parseHostWrite(
        ByteBuffer.wrap(Tn3270TestFixtures.hostQueryReplyRecord(2, 43, 100)), state);

    assertEquals(2, state.activePartitionId());
    assertEquals(43, state.rows());
    assertEquals(100, state.cols());
    assertTrue(state.fields().isEmpty());

    Tn3270SessionState.PartitionSnapshot snapshot = state.partitionSnapshot(2);
    assertEquals(43, snapshot.rows());
    assertEquals(100, snapshot.cols());
    assertEquals(0, snapshot.flags());
  }

}



