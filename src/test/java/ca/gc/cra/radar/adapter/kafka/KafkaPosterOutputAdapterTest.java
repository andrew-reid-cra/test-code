package ca.gc.cra.radar.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaPosterOutputAdapterTest {

  @Test
  void publishesReportsToKafkaTopic() throws Exception {
    MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
    KafkaPosterOutputAdapter adapter = new KafkaPosterOutputAdapter(producer, "radar.http.reports");

    PosterReport report = new PosterReport(ProtocolId.HTTP, "txn", 100L, "content");
    adapter.write(report);
    adapter.close();

    assertEquals(1, producer.history().size());
    var record = producer.history().get(0);
    assertEquals("radar.http.reports", record.topic());
    assertEquals("content", record.value());
  }
}
