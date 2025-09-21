package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.adapter.kafka.HttpKafkaPosterPipeline;
import ca.gc.cra.radar.adapter.kafka.KafkaPosterOutputAdapter;
import ca.gc.cra.radar.adapter.kafka.Tn3270KafkaPosterPipeline;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.DecodeMode;
import ca.gc.cra.radar.config.PosterConfig.ProtocolConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.poster.FilePosterOutputAdapter;
import ca.gc.cra.radar.infrastructure.poster.HttpPosterPipeline;
import ca.gc.cra.radar.infrastructure.poster.Tn3270PosterPipeline;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Coordinates protocol-specific poster pipelines that render reconstructed traffic for reporting.
 * <p>Each configured protocol runs in its own task; not thread-safe for concurrent {@link #run(PosterConfig)}
 * invocations.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class PosterUseCase {
  private static final Logger log = LoggerFactory.getLogger(PosterUseCase.class);

  private final Map<ProtocolId, PosterPipeline> pipelines;

  /**
   * Creates a poster use case with the default protocol pipelines.
   */
  public PosterUseCase() {
    this(defaultPipelines());
  }

  /**
   * Creates a poster use case with custom pipelines.
   *
   * @param pipelines map of protocol identifiers to pipeline implementations
   */
  public PosterUseCase(Map<ProtocolId, PosterPipeline> pipelines) {
    Objects.requireNonNull(pipelines, "pipelines");
    this.pipelines = Map.copyOf(pipelines);
  }

  /**
   * Runs the configured poster pipelines.
   *
   * @param config poster configuration supplied by the CLI layer
   * @throws Exception if any pipeline fails
   */
  public void run(PosterConfig config) throws Exception {
    Objects.requireNonNull(config, "config");
    List<Callable<Void>> tasks = new ArrayList<>();

    addTask(tasks, ProtocolId.HTTP, config.http(), config);
    addTask(tasks, ProtocolId.TN3270, config.tn3270(), config);

    if (tasks.isEmpty()) {
      throw new IllegalArgumentException("poster: no protocol inputs configured");
    }

    if (tasks.size() == 1) {
      tasks.get(0).call();
      return;
    }

    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    try {
      List<Future<Void>> futures = executor.invokeAll(tasks);
      for (Future<Void> future : futures) {
        try {
          future.get();
        } catch (ExecutionException ex) {
          Throwable cause = ex.getCause();
          if (cause instanceof Exception exception) {
            throw exception;
          }
          throw new RuntimeException(cause);
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("Poster pipelines interrupted; requesting shutdown");
      executor.shutdownNow();
      throw ex;
    } finally {
      executor.shutdown();
      log.info("Poster worker pool shutdown initiated");
    }
  }

  private void addTask(
      List<Callable<Void>> tasks,
      ProtocolId protocol,
      Optional<ProtocolConfig> maybeConfig,
      PosterConfig rootConfig) {
    maybeConfig.ifPresent(cfg -> {
      PosterPipeline pipeline = selectPipeline(protocol, cfg, rootConfig);
      PosterOutputPort outputPort = selectOutputPort(protocol, cfg, rootConfig);
      DecodeMode decodeMode = rootConfig.decodeMode();
      tasks.add(() -> {
        String previousProtocol = MDC.get("protocol");
        Exception failure = null;
        try {
          MDC.put("protocol", protocol.name());
          log.info("Poster {} pipeline started", protocol);
          pipeline.process(cfg, decodeMode, outputPort);
          log.info("Poster {} pipeline completed", protocol);
        } catch (Exception ex) {
          failure = ex;
          log.error("Poster {} pipeline failed", protocol, ex);
          throw ex;
        } finally {
          try {
            outputPort.close();
            log.info("Poster {} output closed", protocol);
          } catch (Exception closeEx) {
            if (failure != null) {
              failure.addSuppressed(closeEx);
              log.error("Poster {} output close failure (suppressed)", protocol, closeEx);
            } else {
              log.error("Poster {} output close failure", protocol, closeEx);
              throw closeEx;
            }
          } finally {
            if (previousProtocol == null) {
              MDC.remove("protocol");
            } else {
              MDC.put("protocol", previousProtocol);
            }
          }
        }
        return null;
      });
    });
  }

  private PosterPipeline selectPipeline(
      ProtocolId protocol,
      ProtocolConfig config,
      PosterConfig rootConfig) {
    boolean kafkaInput = config.kafkaInputTopic().isPresent();
    if (!kafkaInput) {
      PosterPipeline pipeline = pipelines.get(protocol);
      if (pipeline == null) {
        throw new IllegalStateException("No poster pipeline registered for " + protocol);
      }
      return pipeline;
    }

    String bootstrap = rootConfig.kafkaBootstrap()
        .orElseThrow(() -> new IllegalArgumentException("kafkaBootstrap required for Kafka poster input"));
    return switch (protocol) {
      case HTTP -> new HttpKafkaPosterPipeline(bootstrap);
      case TN3270 -> new Tn3270KafkaPosterPipeline(bootstrap);
      default -> throw new IllegalArgumentException("Unsupported protocol: " + protocol);
    };
  }

  private PosterOutputPort selectOutputPort(
      ProtocolId protocol,
      ProtocolConfig config,
      PosterConfig rootConfig) {
    IoMode mode = rootConfig.posterOutMode();
    boolean kafkaInput = config.kafkaInputTopic().isPresent();

    if (!kafkaInput) {
      if (mode == IoMode.KAFKA) {
        throw new IllegalArgumentException("posterOutMode=KAFKA requires Kafka input for " + protocol);
      }
      return new FilePosterOutputAdapter(
          config.outputDirectory().orElseThrow(() ->
              new IllegalArgumentException(protocol + " output directory required")),
          protocol);
    }

    if (mode == IoMode.KAFKA) {
      String bootstrap = rootConfig.kafkaBootstrap()
          .orElseThrow(() -> new IllegalArgumentException("kafkaBootstrap required for Kafka poster output"));
      String topic = config.kafkaOutputTopic()
          .orElseThrow(() -> new IllegalArgumentException("Kafka report topic required for " + protocol));
      return new KafkaPosterOutputAdapter(bootstrap, topic);
    }

    return new FilePosterOutputAdapter(
        config.outputDirectory().orElseThrow(() ->
            new IllegalArgumentException(protocol + " output directory required")),
        protocol);
  }

  private static Map<ProtocolId, PosterPipeline> defaultPipelines() {
    Map<ProtocolId, PosterPipeline> map = new EnumMap<>(ProtocolId.class);
    map.put(ProtocolId.HTTP, new HttpPosterPipeline());
    map.put(ProtocolId.TN3270, new Tn3270PosterPipeline());
    return map;
  }
}
