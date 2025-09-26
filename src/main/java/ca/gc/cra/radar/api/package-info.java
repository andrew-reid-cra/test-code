/**
 * CLI entry points that bootstrap RADAR capture, live, assemble, and poster workflows.
 * <p><strong>Role:</strong> Adapter layer on the driving side; parses arguments, configures logging, and invokes use cases.</p>
 * <p><strong>Concurrency:</strong> CLI commands run single-threaded during setup; pipelines spawn their own workers.</p>
 * <p><strong>Performance:</strong> Uses streaming config parsing to keep startup below a few hundred milliseconds.</p>
 * <p><strong>Metrics:</strong> Registers telemetry exporters and surfaces startup metrics such as {@code cli.start.duration.ms}.</p>
 * <p><strong>Security:</strong> Validates user-supplied paths/network targets and redacts secrets in logs.</p>
 */
package ca.gc.cra.radar.api;
