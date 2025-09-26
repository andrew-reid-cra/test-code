package ca.gc.cra.radar.infrastructure.exec;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory helpers for creating tuned executor services aligned with RADAR concurrency requirements.
 */
public final class ExecutorFactories {

  private ExecutorFactories() {}

  /**
   * Builds a fixed-size executor suitable for persistence workers.
   *
   * @param size number of worker threads to allocate
   * @param prefix thread-name prefix used to tag worker threads
   * @param handler uncaught exception handler installed on each worker thread
   * @return configured executor service
   */
  public static ExecutorService newPersistencePool(int size, String prefix, UncaughtExceptionHandler handler) {
    if (size <= 0) {
      throw new IllegalArgumentException("size must be positive");
    }
    String threadPrefix = (prefix == null || prefix.isBlank()) ? "radar-persist" : prefix;
    UncaughtExceptionHandler effectiveHandler = Objects.requireNonNullElse(handler, (t, ex) -> {});
    AtomicInteger index = new AtomicInteger();
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setName(threadPrefix + "-" + index.getAndIncrement());
          thread.setDaemon(false);
          thread.setUncaughtExceptionHandler(effectiveHandler);
          return thread;
        };

    return new ThreadPoolExecutor(
        size,
        size,
        0L,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        factory,
        new ThreadPoolExecutor.AbortPolicy());
  }
}
