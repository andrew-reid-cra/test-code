package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka-backed poster pipeline that consumes serialized HTTP message pairs and renders poster
 * reports from JSON records produced by {@link HttpKafkaPersistenceAdapter}.
 *
 * <p>Refactoring goals:
 * <ul>
 *   <li>Eliminate duplicated JSON/string parsing helpers.</li>
 *   <li>Unify request/response section rendering.</li>
 *   <li>Centralize decoding of transfer/content encodings.</li>
 *   <li>Maintain behavior and keep the hot path allocation-friendly.</li>
 * </ul>
 *
 * @since RADAR 0.1-doc
 */
public final class HttpKafkaPosterPipeline implements PosterPipeline {

  private static final Logger log = LoggerFactory.getLogger(HttpKafkaPosterPipeline.class);

  // ------------ HTTP header constants (single s
