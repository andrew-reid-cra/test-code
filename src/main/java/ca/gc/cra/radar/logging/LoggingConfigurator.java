package ca.gc.cra.radar.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Utility helpers for adjusting logging at runtime from CLI flags.
 */
public final class LoggingConfigurator {
  private static final org.slf4j.Logger log = LoggerFactory.getLogger(LoggingConfigurator.class);

  private LoggingConfigurator() {
    // Utility
  }

  /**
   * Elevates the root logger level to DEBUG for the current JVM.
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
