package ca.gc.cra.radar.application.port.poster;

import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * Protocol-specific pipeline that reads reconstructed message pairs and emits poster reports.
 *
 * @since RADAR 0.1-doc
 */
public interface PosterPipeline {
  /**
   * Returns the protocol handled by this pipeline.
   *
   * @return protocol identifier
   * @since RADAR 0.1-doc
   */
  ProtocolId protocol();

  /**
   * Processes poster inputs and writes rendered output.
   *
   * @param config per-protocol configuration block
   * @param decodeMode level of decoding to apply when rendering content
   * @param outputPort output sink for rendered reports
   * @throws Exception if processing fails
   * @since RADAR 0.1-doc
   */
  void process(PosterConfig.ProtocolConfig config, PosterConfig.DecodeMode decodeMode, PosterOutputPort outputPort) throws Exception;
}
