
package perf;

import ca.gc.cra.radar.application.pipeline.LiveProcessingUseCase;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple load harness that stresses the live persistence queue with synthetic message pairs.
 */
public final class LoadHarness {

  private LoadHarness() {}

  public static void main(String[] args) throws Exception {
    int workers = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    int queueCapacity = workers * 128;
    int totalPairs = 50_000;

    CountingPersistence persistence = new CountingPersistence();
    LiveProcessingUseCase useCase = buildUseCase(workers, queueCapacity, persistence);

    invoke(useCase, "startPersistenceWorkers");

    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    long start = System.nanoTime();
    for (int i = 0; i < totalPairs; i++) {
      MessagePair pair = samplePair(i);
      executor.submit(
          () -> {
            try {
              ((java.util.concurrent.ConcurrentMap<String, String>) pair.metadata().attributes()).put("enqueueNanos", Long.toString(System.nanoTime()));
              invoke(useCase, "enqueueForPersistence", pair);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
    }
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.MINUTES);

    while (persistence.count.get() < totalPairs) {
      Thread.sleep(10);
    }
    long elapsed = System.nanoTime() - start;
    invoke(useCase, "shutdownPersistenceWorkers");

    double seconds = elapsed / 1_000_000_000.0;
    double throughput = persistence.count.get() / seconds;
    List<Long> latencies = persistence.latencies();
    latencies.sort(Comparator.naturalOrder());
    long p95 = percentile(latencies, 0.95);
    long p99 = percentile(latencies, 0.99);

    System.out.printf("Pairs: %d
", persistence.count.get());
    System.out.printf("Throughput: %.2f ops/s
", throughput);
    System.out.printf("Latency p95: %s
", Duration.ofNanos(p95));
    System.out.printf("Latency p99: %s
", Duration.ofNanos(p99));
  }

  private static LiveProcessingUseCase buildUseCase(int workers, int capacity, PersistencePort persistence)
      throws Exception {
    PacketSource packetSource = new NoOpPacketSource();
    FrameDecoder frameDecoder = frame -> Optional.empty();
    FlowAssembler flowAssembler = segment -> Optional.empty();
    ProtocolDetector protocolDetector = (flow, port, bytes) -> ProtocolId.UNKNOWN;
    Map<ProtocolId, java.util.function.Supplier<MessageReconstructor>> reconstructorFactories = Map.of();
    Map<ProtocolId, java.util.function.Supplier<PairingEngine>> pairingFactories = Map.of();
    LiveProcessingUseCase.PersistenceSettings settings =
        new LiveProcessingUseCase.PersistenceSettings(workers, capacity, LiveProcessingUseCase.QueueType.ARRAY);
    return new LiveProcessingUseCase(
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

  private static MessagePair samplePair(int idx) {
    byte[] payload = ("payload-" + idx).getBytes(StandardCharsets.US_ASCII);
    FiveTuple flow = new FiveTuple("127.0.0.1", 10_000 + idx, "127.0.0.2", 8080, "TCP");
    ByteStream stream = new ByteStream(flow, true, payload, System.nanoTime());
    java.util.concurrent.ConcurrentMap<String, String> attributes = new java.util.concurrent.ConcurrentHashMap<>();
    MessageMetadata metadata = new MessageMetadata(null, attributes);
    MessageEvent request = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, stream, metadata);
    MessageEvent response = new MessageEvent(ProtocolId.HTTP, MessageType.RESPONSE, stream, metadata);
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

  private static long percentile(List<Long> values, double percentile) {
    if (values.isEmpty()) {
      return 0L;
    }
    int index = (int) Math.ceil(percentile * values.size()) - 1;
    index = Math.max(0, Math.min(values.size() - 1, index));
    return values.get(index);
  }

  private static final class CountingPersistence implements PersistencePort {
    private final AtomicInteger count = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

    @Override
    public void persist(MessagePair pair) {
      count.incrementAndGet();
      String start = pair.metadata().attributes().get("enqueueNanos");
      if (start != null) {
        long enqueue = Long.parseLong(start);
        latencies.add(System.nanoTime() - enqueue);
      }
    }

    List<Long> latencies() {
      return List.copyOf(latencies);
    }
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
