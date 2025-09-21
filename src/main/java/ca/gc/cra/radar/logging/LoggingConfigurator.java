package ca.gc.cra.radar.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

/**
 * Utility helpers for adjusting logging at runtime from CLI flags.
 */
public final class LoggingConfigurator {
  private LoggingConfigurator() {
    // Utility
  }

  /**
   * Elevates the root logger level to DEBUG for the current JVM.
   */
  public static void enableVerboseLogging() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    if (root.getLevel() != Level.DEBUG) {
      root.setLevel(Level.DEBUG);
    }
  }
}
