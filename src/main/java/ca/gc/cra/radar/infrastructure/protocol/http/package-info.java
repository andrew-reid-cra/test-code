/**
 * HTTP protocol adapters that reconstruct and render application-layer exchanges.
 * <p>Modules translate TCP byte streams into request/response pairs, perform header normalization,
 * and feed poster pipelines for operator review. Implementations assume ASCII/UTF-8 control data
 * and rely on {@link ca.gc.cra.radar.infrastructure.poster.SegmentPosterProcessor} for decoding.</p>
 *
 * @since RADAR 0.1-doc
 */
package ca.gc.cra.radar.infrastructure.protocol.http;
