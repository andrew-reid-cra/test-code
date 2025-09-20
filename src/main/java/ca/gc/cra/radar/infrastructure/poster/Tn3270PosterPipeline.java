package ca.gc.cra.radar.infrastructure.poster;

import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.DecodeMode;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Objects;

public final class Tn3270PosterPipeline implements PosterPipeline {
  private final SegmentPosterProcessor processor;

  public Tn3270PosterPipeline() {
    this(new SegmentPosterProcessor());
  }

  public Tn3270PosterPipeline(SegmentPosterProcessor processor) {
    this.processor = Objects.requireNonNull(processor, "processor");
  }

  @Override
  public ProtocolId protocol() {
    return ProtocolId.TN3270;
  }

  @Override
  public void process(PosterConfig.ProtocolConfig config, DecodeMode decodeMode, PosterOutputPort outputPort) throws Exception {
    Objects.requireNonNull(config, "config");
    processor.process(
        config.inputDirectory().orElseThrow(() -> new IllegalArgumentException("tnIn path required for file mode")),
        config.outputDirectory().orElseThrow(() -> new IllegalArgumentException("tnOut path required for file mode")),
        ProtocolId.TN3270,
        decodeMode);
  }
}


