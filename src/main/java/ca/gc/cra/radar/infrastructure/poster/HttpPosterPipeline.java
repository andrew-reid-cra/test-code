package ca.gc.cra.radar.infrastructure.poster;

import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.DecodeMode;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Objects;

public final class HttpPosterPipeline implements PosterPipeline {
  private final SegmentPosterProcessor processor;

  public HttpPosterPipeline() {
    this(new SegmentPosterProcessor());
  }

  public HttpPosterPipeline(SegmentPosterProcessor processor) {
    this.processor = Objects.requireNonNull(processor, "processor");
  }

  @Override
  public ProtocolId protocol() {
    return ProtocolId.HTTP;
  }

  @Override
  public void process(PosterConfig.ProtocolConfig config, DecodeMode decodeMode) throws Exception {
    Objects.requireNonNull(config, "config");
    processor.process(config.inputDirectory(), config.outputDirectory(), ProtocolId.HTTP, decodeMode);
  }
}
