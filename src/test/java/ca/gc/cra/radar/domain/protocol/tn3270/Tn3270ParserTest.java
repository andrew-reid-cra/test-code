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
}


