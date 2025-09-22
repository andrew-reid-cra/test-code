package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessageMetadata;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveProcessingUseCaseConcurrencyTest {
  private LiveProcessingUseCase useCase;
  private CountingPersistence persistence;

  @BeforeEach
  void setUp() {
    persistence = new CountingPersistence();
    PacketSource packetSource = new NoOpPacketSource();
    FrameDecoder frameDecoder = frame -> Optional.empty();
    FlowAssembler flowAssembler = segment -> Optional.empty();
    ProtocolDetector protocolDetector = (flow, port, bytes) -> ProtocolId.UNKNOWN;
    Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories = Map.of();
    Map<ProtocolId, Supplier<PairingEngine>> pairingFactories = Map.of();
    LiveProcessingUseCase.PersistenceSettings settings =
        new LiveProcessingUseCase.PersistenceSettings(3, 24, LiveProcessingUseCase.QueueType.ARRAY);
    useCase =
        new LiveProcessingUseCase(
            packetSource,
            frameDecoder,
            flowAssembler,
            protocolDetector,
            reconstructorFactories,
            pairingFactories,
            persistence,
            MetricsPort.NO_OP,
            Set.of(ProtocolId.HTTP),
            settings);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (useCase != null) {
      invoke(useCase, "shutdownPersistenceWorkers");
    }
  }

  @Test
  void enqueueUnderLoadPersistsAllPairs() throws Exception {
    invoke(useCase, "startPersistenceWorkers");

    int totalPairs = 200;
    ExecutorService executor = Executors.newFixedThreadPool(4);
    for (int i = 0; i < totalPairs; i++) {
      MessagePair pair = samplePair(i);
      executor.submit(
          () -> {
            try {
              invoke(useCase, "enqueueForPersistence", pair);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
    }
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "executor not drained");

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (persistence.count.get() < totalPairs && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    invoke(useCase, "shutdownPersistenceWorkers");
    assertEquals(totalPairs, persistence.count.get());
  }

  private MessagePair samplePair(int idx) {
    byte[] payload = ("payload-" + idx).getBytes();
    FiveTuple flow = new FiveTuple("10.0.0.1", 1000 + idx, "10.0.0.2", 80, "TCP");
    ByteStream stream = new ByteStream(flow, true, payload, System.nanoTime());
    MessageEvent request = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, stream, MessageMetadata.empty());
    MessageEvent response = new MessageEvent(ProtocolId.HTTP, MessageType.RESPONSE, stream, MessageMetadata.empty());
    return new MessagePair(request, response);
  }

  private static Object invoke(Object target, String methodName, Object... args) throws Exception {
    Class<?>[] types = new Class<?>[args.length];
    for (int i = 0; i < args.length; i++) {
      types[i] = args[i].getClass();
    }
    Method method = target.getClass().getDeclaredMethod(methodName, types);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static final class CountingPersistence implements PersistencePort {
    private final AtomicInteger count = new AtomicInteger();

    @Override
    public void persist(MessagePair pair) {
      count.incrementAndGet();
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private static final class NoOpPacketSource implements PacketSource {
    @Override
    public void start() {}

    @Override
    public Optional<RawFrame> poll() {
      return Optional.empty();
    }

    @Override
    public void close() {}
  }
}
