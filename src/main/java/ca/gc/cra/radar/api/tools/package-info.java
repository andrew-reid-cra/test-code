/**
 * Auxiliary CLI tools for RADAR operators (segment introspection, diagnostics).
 * <p><strong>Role:</strong> Adapter-side utilities built on top of core pipelines.</p>
 * <p><strong>Concurrency:</strong> Commands run single-threaded; heavy lifting delegated to application services.</p>
 * <p><strong>Performance:</strong> Designed for interactive use; streams outputs to avoid loading entire files.</p>
 * <p><strong>Metrics:</strong> Emits {@code cli.tool.*} counters where relevant.</p>
 * <p><strong>Security:</strong> Reuses validation utilities to keep file and network operations scoped to operator intent.</p>
 */
package ca.gc.cra.radar.api.tools;
