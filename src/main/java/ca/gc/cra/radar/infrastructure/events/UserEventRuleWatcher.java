package ca.gc.cra.radar.infrastructure.events;

import ca.gc.cra.radar.application.events.rules.CompiledRuleSet;
import ca.gc.cra.radar.application.events.rules.UserEventRuleEngine;
import ca.gc.cra.radar.application.events.rules.UserEventRuleSetProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Polling watcher that reloads user event rule files when their modification times change.
 *
 * @since RADAR 1.1.0
 */
public final class UserEventRuleWatcher {
  private static final long DEFAULT_INTERVAL_MILLIS = 5_000L;

  private final List<Path> rulePaths;
  private final UserEventRuleSetProvider provider;
  private final UserEventRuleEngine engine;
  private final Logger log;
  private final long intervalMillis;

  private volatile long nextCheckAt;
  private Map<Path, FileTime> lastSnapshot;

  public UserEventRuleWatcher(
      List<Path> rulePaths,
      UserEventRuleSetProvider provider,
      UserEventRuleEngine engine,
      Logger log) {
    this(rulePaths, provider, engine, log, DEFAULT_INTERVAL_MILLIS);
  }

  public UserEventRuleWatcher(
      List<Path> rulePaths,
      UserEventRuleSetProvider provider,
      UserEventRuleEngine engine,
      Logger log,
      long intervalMillis) {
    this.rulePaths = List.copyOf(Objects.requireNonNull(rulePaths, "rulePaths"));
    this.provider = Objects.requireNonNull(provider, "provider");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.log = Objects.requireNonNull(log, "log");
    this.intervalMillis = Math.max(1_000L, intervalMillis);
    this.lastSnapshot = snapshot();
  }

  /**
   * Checks for modified rule files and reloads when necessary.
   */
  public void maybeReload() {
    if (rulePaths.isEmpty()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now < nextCheckAt) {
      return;
    }
    nextCheckAt = now + intervalMillis;

    Map<Path, FileTime> current = snapshot();
    if (!hasChanged(current)) {
      return;
    }

    try {
      CompiledRuleSet ruleSet = provider.load(rulePaths);
      engine.update(ruleSet);
      lastSnapshot = current;
      if (engine.hasRules()) {
        log.info("Reloaded user event rules from {}", rulePaths);
      } else {
        log.info("User event rules cleared after reload of {}", rulePaths);
      }
    } catch (IOException ex) {
      log.warn("Failed to reload user event rules from {}", rulePaths, ex);
    }
  }

  private boolean hasChanged(Map<Path, FileTime> current) {
    if (current.size() != lastSnapshot.size()) {
      return true;
    }
    for (Map.Entry<Path, FileTime> entry : current.entrySet()) {
      FileTime previous = lastSnapshot.get(entry.getKey());
      if (previous == null || !previous.equals(entry.getValue())) {
        return true;
      }
    }
    return false;
  }

  private Map<Path, FileTime> snapshot() {
    Map<Path, FileTime> snapshot = new HashMap<>();
    for (Path path : rulePaths) {
      try {
        if (Files.exists(path)) {
          snapshot.put(path, Files.getLastModifiedTime(path));
        }
      } catch (IOException ex) {
        log.warn("Unable to read modification time for user event rule {}", path, ex);
      }
    }
    return snapshot;
  }
}
