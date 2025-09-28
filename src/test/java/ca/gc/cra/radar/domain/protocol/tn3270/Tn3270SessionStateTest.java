package ca.gc.cra.radar.domain.protocol.tn3270;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Tn3270SessionStateTest {

  @Test
  void selectPartitionInitialisesWithDefaultGeometry() {
    Tn3270SessionState state = new Tn3270SessionState();

    state.selectPartition(3);

    assertEquals(3, state.activePartitionId());
    assertEquals(Tn3270SessionState.DEFAULT_ROWS, state.rows());
    assertEquals(Tn3270SessionState.DEFAULT_COLS, state.cols());
  }

  @Test
  void configurePartitionAppliesGeometryAndClearsBuffer() {
    Tn3270SessionState state = new Tn3270SessionState();

    state.configurePartition(1, 32, 80, true);

    assertEquals(1, state.activePartitionId());
    assertEquals(32, state.rows());
    assertEquals(80, state.cols());
    for (byte b : state.buffer()) {
      assertEquals((byte) 0x40, b);
    }
  }

  @Test
  void fieldMetadataTracksPartitionIdentity() {
    Tn3270SessionState state = new Tn3270SessionState();

    state.startField(0, (byte) 0x20);
    state.finalizeFields();
    assertEquals(0, state.fields().get(0).partitionId());

    state.configurePartition(2, 24, 80, true);
    state.startField(0, (byte) 0x20);
    state.finalizeFields();
    assertEquals(2, state.fields().get(0).partitionId());
  }

  @Test
  void configurePartitionWithAttributesStoresMetadata() {
    Tn3270SessionState state = new Tn3270SessionState();

    state.configurePartition(0, 24, 80, true, 0x7F, new byte[] {(byte) 0x11, (byte) 0x22});

    assertEquals(0x7F, state.partitionFlags());
    assertArrayEquals(new byte[] {(byte) 0x11, (byte) 0x22}, state.partitionAttributes());

    byte[] copy = state.partitionAttributes();
    copy[0] = 0x00;
    assertNotEquals(copy[0], state.partitionAttributes()[0]);
  }

  @Test
  void partitionSnapshotExposesFrozenView() {
    Tn3270SessionState state = new Tn3270SessionState();

    state.startField(0, (byte) 0x20);
    state.finalizeFields();

    Tn3270SessionState.PartitionSnapshot snapshot = state.partitionSnapshot(0);

    assertEquals(0, snapshot.id());
    assertEquals(Tn3270SessionState.DEFAULT_ROWS, snapshot.rows());
    assertEquals(Tn3270SessionState.DEFAULT_COLS, snapshot.cols());
    assertEquals((byte) 0x20, snapshot.fields().get(0).attribute());

    snapshot.buffer()[0] = 0x00;
    assertEquals((byte) 0x20, state.buffer()[0]);
  }

}
