package ca.gc.cra.radar.infrastructure.protocol.tn3270;

import ca.gc.cra.radar.application.port.Tn3270AssemblerPort;
import ca.gc.cra.radar.application.port.Tn3270EventSink;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.tn3270.AidKey;
import ca.gc.cra.radar.domain.protocol.tn3270.ScreenField;
import ca.gc.cra.radar.domain.protocol.tn3270.ScreenSnapshot;
import ca.gc.cra.radar.domain.protocol.tn3270.SessionKey;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Event;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270EventType;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Parser;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270Parser.SubmitResult;
import ca.gc.cra.radar.domain.protocol.tn3270.Tn3270SessionState;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembler adapter that converts TN3270 message pairs into structured events.
 *
 * @since RADAR 0.2.0
 */
public final class Tn3270AssemblerAdapter implements Tn3270AssemblerPort {
  private static final Logger log = LoggerFactory.getLogger(Tn3270AssemblerAdapter.class);
  private static final String OTEL_SCOPE = "radar/tn3270/assembler";

  private final Tn3270EventSink sink;
  private final TelnetNegotiationFilter filter;
  private final ConcurrentHashMap<SessionKey, SessionContext> sessions = new ConcurrentHashMap<>(); // TODO: add idle eviction once session end detection is available.
  private final Function<Map<String, String>, Map<String, String>> redaction;
  private final boolean emitScreenRenders;
  private final double screenRenderSampleRate;

  private final Tracer tracer;
  private final LongCounter renderCounter;
  private final LongCounter submitCounter;
  private final LongCounter bytesCounter;
  private final LongHistogram parseLatency;
  private final LongUpDownCounter sessionsActive;
  private final Attributes telemetryAttributes;

  /**
   * Creates an assembler adapter.
   *
   * @param sink event sink receiving assembled events
   * @param filter telnet negotiation filter
   * @param redaction redaction policy applied to user input maps
   * @param emitScreenRenders whether SCREEN_RENDER events should be emitted
   * @param screenRenderSampleRate probability in [0,1] for emitting SCREEN_RENDER events
   */
  public Tn3270AssemblerAdapter(
      Tn3270EventSink sink,
      TelnetNegotiationFilter filter,
      Function<Map<String, String>, Map<String, String>> redaction,
      boolean emitScreenRenders,
      double screenRenderSampleRate) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.filter = Objects.requireNonNullElseGet(filter, TelnetNegotiationFilter::new);
    this.redaction = Objects.requireNonNullElse(redaction, Function.identity());
    this.emitScreenRenders = emitScreenRenders;
    this.screenRenderSampleRate = clampRate(screenRenderSampleRate);

    Meter meter = GlobalOpenTelemetry.getMeter(OTEL_SCOPE);
    this.tracer = GlobalOpenTelemetry.getTracer(OTEL_SCOPE);
    this.telemetryAttributes = Attributes.builder().put("protocol", "tn3270").build();
    this.renderCounter =
        meter.counterBuilder("tn3270.events.render.count")
            .setUnit("1")
            .setDescription("TN3270 screen render events emitted by the assembler")
            .build();
    this.submitCounter =
        meter.counterBuilder("tn3270.events.submit.count")
            .setUnit("1")
            .setDescription("TN3270 user submit events emitted by the assembler")
            .build();
    this.bytesCounter =
        meter.counterBuilder("tn3270.bytes.processed")
            .setUnit("By")
            .setDescription("TN3270 bytes processed after Telnet filtering")
            .build();
    this.parseLatency =
        meter.histogramBuilder("tn3270.parse.latency.ms")
            .ofLongs()
            .setUnit("ms")
            .setDescription("Parser latency per TN3270 record")
            .build();
    this.sessionsActive =
        meter.upDownCounterBuilder("tn3270.sessions.active")
            .setUnit("1")
            .setDescription("Active TN3270 sessions tracked by the assembler")
            .build();
  }

  private static double clampRate(double rate) {
    if (Double.isNaN(rate) || rate < 0d) {
      return 0d;
    }
    if (rate > 1d) {
      return 1d;
    }
    return rate;
  }

  @Override
  public void onPair(MessagePair pair) throws Exception {
    if (pair == null) {
      return;
    }
    SessionKey sessionKey = resolveSessionKey(pair);
    if (sessionKey == null) {
      log.warn("tn3270 assembler could not derive session key for pair");
      return;
    }
    SessionContext context = sessions.computeIfAbsent(sessionKey, this::createSession);
    maybeEmitSessionStart(context, pair);

    MessageEvent response = pair.response();
    if (response != null) {
      processResponse(context, response);
    }

    MessageEvent request = pair.request();
    if (request != null) {
      processRequest(context, request);
    }
  }

  private SessionContext createSession(SessionKey key) {
    sessionsActive.add(1, telemetryAttributes);
    log.debug("tn3270 session created: {}", key.canonical());
    return new SessionContext(key);
  }

  private void maybeEmitSessionStart(SessionContext context, MessagePair pair) {
    if (context.started) {
      return;
    }
    context.started = true;
    long timestamp = firstTimestampMicros(pair).orElseGet(this::nowMicros);
    Tn3270Event event =
        Tn3270Event.Builder.create(Tn3270EventType.SESSION_START, context.key, timestamp).build();
    emitEvent(event);
  }

  private void processResponse(SessionContext context, MessageEvent response) {
    ByteStream payload = response.payload();
    if (payload == null || payload.data().length == 0) {
      return;
    }
    ByteBuffer filtered = filter.filter(ByteBuffer.wrap(payload.data()));
    int processedBytes = filtered.remaining();
    long startNanos = System.nanoTime();
    Span span = tracer.spanBuilder("tn3270.parse.host_write").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      bytesCounter.add(processedBytes, telemetryAttributes);
      ScreenSnapshot snapshot = Tn3270Parser.parseHostWrite(filtered, context.state);
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      parseLatency.record(durationMs, telemetryAttributes);
      String screenHash = context.state.lastScreenHash();
      String screenName = deriveScreenName(context, snapshot);
      span.setAttribute("tn3270.screen.hash", screenHash == null ? "" : screenHash);
      span.setAttribute("tn3270.session", context.key.canonical());

      if (shouldEmitRender()) {
        Tn3270Event event =
            Tn3270Event.Builder.create(Tn3270EventType.SCREEN_RENDER, context.key, payload.timestampMicros())
                .screen(snapshot)
                .screenHash(screenHash)
                .screenName(screenName)
                .build();
        emitEvent(event);
        renderCounter.add(1, telemetryAttributes);
      }
    } catch (Exception ex) {
      span.recordException(ex);
      span.setStatus(StatusCode.ERROR, ex.getMessage() == null ? "host parse error" : ex.getMessage());
      log.error("Failed to parse TN3270 host write for session {}", context.key, ex);
      throw ex;
    } finally {
      span.end();
    }
  }

  private void processRequest(SessionContext context, MessageEvent request) {
    ByteStream payload = request.payload();
    if (payload == null || payload.data().length == 0) {
      return;
    }
    ByteBuffer filtered = filter.filter(ByteBuffer.wrap(payload.data()));
    int processedBytes = filtered.remaining();
    long startNanos = System.nanoTime();
    Span span = tracer.spanBuilder("tn3270.parse.client_submit").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      bytesCounter.add(processedBytes, telemetryAttributes);
      SubmitResult result = Tn3270Parser.parseClientSubmit(filtered, context.state);
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      parseLatency.record(durationMs, telemetryAttributes);

      Map<String, String> sanitizedInputs = redaction.apply(result.inputs());
      AidKey aid = result.aid();
      span.setAttribute("tn3270.aid", aid.name());
      span.setAttribute("tn3270.session", context.key.canonical());

      Tn3270Event event =
          Tn3270Event.Builder.create(Tn3270EventType.USER_SUBMIT, context.key, payload.timestampMicros())
              .aid(aid)
              .inputFields(sanitizedInputs)
              .screenHash(context.state.lastScreenHash())
              .screenName(context.screenName)
              .build();
      emitEvent(event);
      submitCounter.add(1, telemetryAttributes);
    } catch (Exception ex) {
      span.recordException(ex);
      span.setStatus(StatusCode.ERROR, ex.getMessage() == null ? "client parse error" : ex.getMessage());
      log.error("Failed to parse TN3270 client submit for session {}", context.key, ex);
      throw ex;
    } finally {
      span.end();
    }
  }

  private boolean shouldEmitRender() {
    if (!emitScreenRenders) {
      return false;
    }
    if (screenRenderSampleRate >= 1d) {
      return true;
    }
    if (screenRenderSampleRate <= 0d) {
      return false;
    }
    return ThreadLocalRandom.current().nextDouble() < screenRenderSampleRate;
  }

  private String deriveScreenName(SessionContext context, ScreenSnapshot snapshot) {
    if (snapshot == null) {
      return context.screenName;
    }
    if (context.screenName != null && !context.screenName.isBlank()) {
      return context.screenName;
    }
    List<String> candidates = new ArrayList<>();
    for (ScreenField field : snapshot.fields()) {
      if (field.protectedField()) {
        String candidate = sanitizeScreenName(field.value());
        if (candidate != null) {
          candidates.add(candidate);
        }
      }
    }
    if (candidates.isEmpty()) {
      String[] lines = snapshot.plainText().split("\n");
      if (lines.length > 0) {
        String candidate = sanitizeScreenName(lines[0]);
        if (candidate != null) {
          candidates.add(candidate);
        }
      }
    }
    if (!candidates.isEmpty()) {
      context.screenName = candidates.get(0);
    }
    return context.screenName;
  }

  private static String sanitizeScreenName(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    String normalized = trimmed.replaceAll("[^A-Za-z0-9 ]", " ").trim();
    if (normalized.isEmpty()) {
      return null;
    }
    return normalized.toUpperCase(Locale.ROOT).replace(' ', '_');
  }

  private SessionKey resolveSessionKey(MessagePair pair) {
    MessageEvent response = pair.response();
    if (response != null && response.payload() != null) {
      return keyFromPayload(response.payload());
    }
    MessageEvent request = pair.request();
    if (request != null && request.payload() != null) {
      return keyFromPayload(request.payload());
    }
    return null;
  }

  private SessionKey keyFromPayload(ByteStream payload) {
    FiveTuple flow = payload.flow();
    if (flow == null) {
      return null;
    }
    boolean fromClient = payload.fromClient();
    String clientIp = fromClient ? flow.srcIp() : flow.dstIp();
    int clientPort = fromClient ? flow.srcPort() : flow.dstPort();
    String serverIp = fromClient ? flow.dstIp() : flow.srcIp();
    int serverPort = fromClient ? flow.dstPort() : flow.srcPort();
    return new SessionKey(clientIp, clientPort, serverIp, serverPort, null);
  }

  private Optional<Long> firstTimestampMicros(MessagePair pair) {
    MessageEvent response = pair.response();
    if (response != null && response.payload() != null) {
      return Optional.of(response.payload().timestampMicros());
    }
    MessageEvent request = pair.request();
    if (request != null && request.payload() != null) {
      return Optional.of(request.payload().timestampMicros());
    }
    return Optional.empty();
  }

  private void emitEvent(Tn3270Event event) {
    try {
      sink.accept(event);
    } catch (Exception ex) {
      log.error("Failed to emit TN3270 event {} for session {}", event.type(), event.session(), ex);
    }
  }

  @Override
  public void close() throws Exception {
    for (SessionContext context : sessions.values()) {
      emitSessionEnd(context);
      sessionsActive.add(-1, telemetryAttributes);
    }
    sessions.clear();
    sink.close();
  }

  private void emitSessionEnd(SessionContext context) {
    Tn3270Event event =
        Tn3270Event.Builder.create(Tn3270EventType.SESSION_END, context.key, nowMicros())
            .screenName(context.screenName)
            .build();
    emitEvent(event);
  }

  private long nowMicros() {
    return Instant.now().toEpochMilli() * 1_000;
  }

  private static final class SessionContext {
    private final SessionKey key;
    private final Tn3270SessionState state = new Tn3270SessionState();
    private String screenName;
    private boolean started;

    private SessionContext(SessionKey key) {
      this.key = key;
    }
  }
}





