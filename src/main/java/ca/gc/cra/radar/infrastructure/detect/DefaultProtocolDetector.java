package ca.gc.cra.radar.infrastructure.detect;

import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.application.port.ProtocolModule;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Default detector leveraging enabled protocol modules, first matching by configured ports then
 * falling back to signature heuristics.
 * <p>Thread-safe for concurrent classification requests once constructed.</p>
 */
public final class DefaultProtocolDetector implements ProtocolDetector {
  private final List<ProtocolModule> modules;
  private final Set<ProtocolId> enabled;

  /**
   * Creates a detector using the supplied protocol modules and enabled set.
   *
   * @param modules protocol modules to consult
   * @param enabled subset of protocols that should be considered
   * @throws NullPointerException if {@code modules} or {@code enabled} is {@code null}
   * @since RADAR 0.1-doc
   */
  public DefaultProtocolDetector(Collection<ProtocolModule> modules, Set<ProtocolId> enabled) {
    this.modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
    this.enabled = Set.copyOf(Objects.requireNonNull(enabled, "enabled"));
  }

  /**
   * Classifies a flow using port hints followed by signature heuristics.
   *
   * @param flow five-tuple describing the connection
   * @param serverPortHint optional server port hint (0 or negative ignored)
   * @param prefacePeek leading bytes from the stream for signature detection; may be {@code null}
   * @return detected protocol id, or {@link ProtocolId#UNKNOWN} when ambiguous or unsupported
   * @throws NullPointerException if {@code flow} is {@code null}
   * @implNote Prefers port-based detection before inspecting signatures to avoid expensive heuristics.
   * @since RADAR 0.1-doc
   */
  @Override
  public ProtocolId classify(FiveTuple flow, int serverPortHint, byte[] prefacePeek) {
    Objects.requireNonNull(flow, "flow");
    List<ProtocolModule> candidates = new ArrayList<>();

    int candidatePort = serverPortHint > 0 ? serverPortHint : flow.dstPort();

    for (ProtocolModule module : modules) {
      if (!enabled.contains(module.id())) continue;
      if (module.defaultServerPorts().contains(candidatePort)) {
        candidates.add(module);
      }
    }

    if (candidates.size() == 1) {
      return candidates.get(0).id();
    }

    // Either zero or multiple candidates by port; apply signature heuristics.
    ProtocolModule matched = null;
    for (ProtocolModule module : modules) {
      if (!enabled.contains(module.id())) continue;
      if (module.matchesSignature(prefacePeek)) {
        if (matched != null) {
          // Multiple matches -> ambiguous, fall back to UNKNOWN.
          return ProtocolId.UNKNOWN;
        }
        matched = module;
      }
    }

    return matched != null ? matched.id() : ProtocolId.UNKNOWN;
  }
}



