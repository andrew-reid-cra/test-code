package ca.gc.cra.radar.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * <strong>What:</strong> Configures RADAR runtime logging for CLI-driven workflows.
 * <p><strong>Why:</strong> Allows operators to elevate verbosity during troubleshooting without editing config files.
 * <p><strong>Role:</strong> Adapter-side utility that bridges CLI flags to the logging backend.
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Detect the active SLF4J implementation and adjust the root logging level.</li>
 *   <li>Warn when the backend does not support dynamic level changes.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Methods synchronize via the underlying logging framework; safe for single
 * initialization thread during CLI bootstrap.</p>
 * <p><strong>Performance:</strong> One-off logger lookups; no hot-path allocations.</p>
 * <p><strong>Observability:</strong> Emits SLF4J warnings when dynamic configuration is unsupported.</p>
 *
 * @implNote Tailored for Logback; other SLF4J bindings fall back to warning and retain defaults.
 * @since 0.1.0
 * @see Logs
 */
public final class LoggingConfigurator {
  private static final org.slf4j.Logger log = LoggerFactory.getLogger(LoggingConfigurator.class);

  private LoggingConfigurator() {
    // Utility
  }

  /**
   * Elevates the root logger level to DEBUG within the running JVM.
   *
   * <p><strong>Concurrency:</strong> Intended for single-threaded CLI startup; races with concurrent logback
   * reconfiguration may produce undefined results.</p>
   * <p><strong>Performance:</strong> Single SLF4J factory lookup and potential level mutation.</p>
   * <p><strong>Observability:</strong> Logs a warning naming the SLF4J backend when dynamic level updates fail.</p>
   */
  public static void enableVerboseLogging() {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext context) {
      Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      if (!Level.DEBUG.equals(root.getLevel())) {
        root.setLevel(Level.DEBUG);
      }
      return;
    }
    log.warn("Verbose logging requested but backend {} does not support dynamic level updates",
        factory.getClass().getName());
  }
}
