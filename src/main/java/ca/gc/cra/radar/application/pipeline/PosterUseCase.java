package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.DecodeMode;
import ca.gc.cra.radar.config.PosterConfig.ProtocolConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
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

public final class PosterUseCase {
  private final Map<ProtocolId, PosterPipeline> pipelines;

  public PosterUseCase() {
    this(defaultPipelines());
  }

  public PosterUseCase(Map<ProtocolId, PosterPipeline> pipelines) {
    Objects.requireNonNull(pipelines, "pipelines");
    this.pipelines = Map.copyOf(pipelines);
  }

  public void run(PosterConfig config) throws Exception {
    Objects.requireNonNull(config, "config");
    List<Callable<Void>> tasks = new ArrayList<>();

    addTask(tasks, ProtocolId.HTTP, config.http(), config.decodeMode());
    addTask(tasks, ProtocolId.TN3270, config.tn3270(), config.decodeMode());

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
    } finally {
      executor.shutdown();
    }
  }

  private void addTask(
      List<Callable<Void>> tasks,
      ProtocolId protocol,
      Optional<ProtocolConfig> maybeConfig,
      DecodeMode decodeMode) {
    maybeConfig.ifPresent(cfg -> {
      PosterPipeline pipeline = pipelines.get(protocol);
      if (pipeline == null) {
        throw new IllegalStateException("No poster pipeline registered for " + protocol);
      }
      tasks.add(() -> {
        pipeline.process(cfg, decodeMode);
        return null;
      });
    });
  }

  private static Map<ProtocolId, PosterPipeline> defaultPipelines() {
    Map<ProtocolId, PosterPipeline> map = new EnumMap<>(ProtocolId.class);
    map.put(ProtocolId.HTTP, new HttpPosterPipeline());
    map.put(ProtocolId.TN3270, new Tn3270PosterPipeline());
    return map;
  }
}

