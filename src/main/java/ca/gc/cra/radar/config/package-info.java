/**
 * Configuration aggregates and composition root wiring for RADAR CLIs.
 * <p><strong>Role:</strong> Application bootstrap layer selecting capture, assemble, and sink adapters.</p>
 * <p><strong>Concurrency:</strong> Configuration objects are immutable; safe to share.</p>
 * <p><strong>Performance:</strong> Parsing favors streaming readers to keep CLI startup fast.</p>
 * <p><strong>Metrics:</strong> Emits startup metrics such as {@code config.load.duration.ms}.</p>
 * <p><strong>Security:</strong> Validates paths and redacts secrets from logs; relies on {@code ca.gc.cra.radar.validation} utilities.</p>
 */
package ca.gc.cra.radar.config;
