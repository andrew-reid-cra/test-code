/**
 * Command-line entry points for RADAR capture, live, assemble, and poster workflows.
 * <p>Parses {@code key=value} arguments into strongly typed configuration records, applies
 * validation (including BPF gating and safe defaults), and delegates to application pipelines.</p>
 * <p>CLI classes also encode structured exit codes and logging categories so operators can
 * distinguish configuration, IO, and processing faults quickly.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.api;
