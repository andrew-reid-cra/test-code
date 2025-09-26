/**
 * Domain utility classes for byte and encoding helpers.
 * <p><strong>Role:</strong> Domain support functions reused by capture and assemble stages.</p>
 * <p><strong>Concurrency:</strong> Utilities are stateless; safe to call concurrently.</p>
 * <p><strong>Performance:</strong> Implemented with allocation-free codecs for hot paths.</p>
 * <p><strong>Metrics:</strong> Do not emit metrics; callers observe usage.</p>
 * <p><strong>Security:</strong> Ensure input validation to prevent malformed data propagation.</p>
 */
package ca.gc.cra.radar.domain.util;
