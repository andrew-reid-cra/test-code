/**
 * TN3270 protocol adapters responsible for decoding screen flows and pairing transactions.
 * <p>Components convert ordered TCP payloads into terminal records, surface metrics, and
 * integrate with the poster pipelines for archival and analysis.</p>
 * <p>Implementations expect big-endian host data and enforce strict framing validation to guard
 * against malformed or malicious input.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.protocol.tn3270;
