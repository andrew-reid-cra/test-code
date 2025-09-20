package ca.gc.cra.radar.application.port.poster;

import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;

/** Protocol-specific poster pipeline contract. */
public interface PosterPipeline {
  ProtocolId protocol();

  void process(PosterConfig.ProtocolConfig config, PosterConfig.DecodeMode decodeMode, PosterOutputPort outputPort) throws Exception;
}

