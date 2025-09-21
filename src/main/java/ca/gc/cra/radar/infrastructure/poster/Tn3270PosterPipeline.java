package ca.gc.cra.radar.infrastructure.poster;

import ca.gc.cra.radar.application.port.poster.PosterOutputPort;
import ca.gc.cra.radar.application.port.poster.PosterPipeline;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.DecodeMode;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.Objects;

/**
 * File-based poster pipeline for TN3270 message pairs.
 * <p>Delegates rendering to {@link SegmentPosterProcessor} using filesystem inputs/outputs.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class Tn3270PosterPipeline implements PosterPipeline {
  private final SegmentPosterProcessor processor;

  /**
   * Creates a pipeline using the default {@link SegmentPosterProcessor}.
   *
   * @since RADAR 0.1-doc
   */
  public Tn3270PosterPipeline() {
    this(new SegmentPosterProcessor());
  }

  /**
   * Creates a pipeline with a custom processor (primarily for tests).
   *
   * @param processor poster processor used to render segments
   * @throws NullPointerException if {@code processor} is {@code null}
   * @since RADAR 0.1-doc
   */
  public Tn3270PosterPipeline(SegmentPosterProcessor processor) {
    this.processor = Objects.requireNonNull(processor, "processor");
  }

  /**
   * Identifies the protocol handled by this pipeline.
   *
   * @return {@link ProtocolId#TN3270}
   * @since RADAR 0.1-doc
   */
  @Override
  public ProtocolId protocol() {
    return ProtocolId.TN3270;
  }

  /**
   * Renders TN3270 pairs from the configured input directory into the output directory.
   *
   * @param config per-protocol configuration (file paths required)
   * @param decodeMode decode mode controlling payload processing
   * @param outputPort ignored (TN3270 file pipeline writes via {@link SegmentPosterProcessor})
   * @throws NullPointerException if {@code config} is {@code null}
   * @throws Exception if reading or rendering fails
   * @implNote Operates solely on filesystem inputs/outputs; {@code outputPort} is unused.
   * @since RADAR 0.1-doc
   */
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
