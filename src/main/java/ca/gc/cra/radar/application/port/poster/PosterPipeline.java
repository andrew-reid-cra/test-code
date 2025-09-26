package ca.gc.cra.radar.application.port.poster;

import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * <strong>What:</strong> Domain port for protocol-specific poster pipelines that render message pairs.
 * <p><strong>Why:</strong> Separates poster rendering per protocol so new formats can be added without touching the CLI.</p>
 * <p><strong>Role:</strong> Domain port implemented by poster adapters.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Declare the supported {@link ProtocolId}.</li>
 *   <li>Process reconstructed pairs and emit poster output.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations typically process single-threaded pipelines per CLI invocation.</p>
 * <p><strong>Performance:</strong> Expected to stream outputs; avoid buffering entire captures.</p>
 * <p><strong>Observability:</strong> Implementations emit {@code poster.pipeline.*} metrics describing throughput and errors.</p>
 *
 * @implNote Pipelines should treat {@link PosterConfig.DecodeMode} as a hard contract for decoding depth.
 * @since 0.1.0
 */
public interface PosterPipeline {
  /**
   * Identifies the protocol handled by this pipeline.
   *
   * @return supported {@link ProtocolId}; never {@code null}
   *
   * <p><strong>Concurrency:</strong> Safe for single-threaded access during pipeline bootstrap.</p>
   * <p><strong>Performance:</strong> Constant-time lookup.</p>
   * <p><strong>Observability:</strong> Callers may log the selected protocol for traceability.</p>
   */
  ProtocolId protocol();

  /**
   * Processes poster inputs and writes rendered output.
   *
   * @param config per-protocol configuration providing tuning knobs and limits; must not be {@code null}
   * @param decodeMode decoding depth controlling how much content is rendered; must not be {@code null}
   * @param outputPort sink for rendered reports; must not be {@code null}
   * @throws Exception if processing fails or the output port reports an error
   *
   * <p><strong>Concurrency:</strong> Implementations typically run single-threaded; coordinate additional workers internally if needed.</p>
   * <p><strong>Performance:</strong> Expected to stream results; may buffer only a few message pairs.</p>
   * <p><strong>Observability:</strong> Should emit poster metrics (e.g., {@code poster.pipeline.rendered}) and log summary statistics.</p>
   */
  void process(PosterConfig.ProtocolConfig config, PosterConfig.DecodeMode decodeMode, PosterOutputPort outputPort) throws Exception;
}

