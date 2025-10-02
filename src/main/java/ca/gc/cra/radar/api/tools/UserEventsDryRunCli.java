package ca.gc.cra.radar.api.tools;

import ca.gc.cra.radar.api.CliArgsParser;
import ca.gc.cra.radar.api.CliInput;
import ca.gc.cra.radar.api.CliPrinter;
import ca.gc.cra.radar.api.ExitCode;
import ca.gc.cra.radar.application.events.SessionResolver;
import ca.gc.cra.radar.application.events.UserEventAssembler;
import ca.gc.cra.radar.application.events.http.HttpExchangeBuilder;
import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.rules.CompiledRuleSet;
import ca.gc.cra.radar.application.events.rules.RuleMatchResult;
import ca.gc.cra.radar.application.events.rules.UserEventRuleEngine;
import ca.gc.cra.radar.application.events.rules.UserEventRuleSetProvider;
import ca.gc.cra.radar.domain.events.UserEvent;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.msg.MessageType;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI that loads user-event rules and evaluates them against sample HTTP exchanges.
 */
public final class UserEventsDryRunCli {
  private static final Logger log = LoggerFactory.getLogger(UserEventsDryRunCli.class);
  private static final String SAMPLE_PREFIX = "Sample ";
  private static final String SUMMARY_USAGE =
      "usage: user-events [rules=config/user-events.yaml] [samples=dev/samples/http]";
  private static final String HELP_TEXT = """
      RADAR user event dry-run utility

      Usage:
        user-events [rules=config/user-events.yaml] [samples=dev/samples/http]

      Options:
        rules=PATHS           Comma or semicolon separated list of rule files to load
        samples=DIR           Directory containing <name>-request.txt and <name>-response.txt pairs

      Flags:
        --help               Show this message
        --verbose            Enable verbose logging

      Example:
        radar user-events rules=config/user-events.yaml samples=dev/samples/http
      """;

  private UserEventsDryRunCli() {}

  /**
   * Executes the dry-run workflow for user event rules.
   *
   * <p>Parses command-line options, loads the requested rule files, builds sample HTTP exchanges,
   * and prints any generated user events. The CLI returns a non-zero exit code whenever the
   * supplied arguments are invalid or an unrecoverable IO error occurs.
   *
   * @param args raw CLI arguments supplied by the launcher
   * @return success or failure exit code communicated to the invoking shell
   */
  public static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);
    if (input.help()) {
      CliPrinter.println(HELP_TEXT.strip());
      return ExitCode.SUCCESS;
    }
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.info("Verbose logging enabled for user-events dry run");
    }

    Map<String, String> options = CliArgsParser.toMap(input.keyValueArgs());
    List<Path> rulePaths;
    try {
      rulePaths = resolveRulePaths(options.get("rules"));
    } catch (IllegalArgumentException ex) {
      CliPrinter.println(ex.getMessage());
      return ExitCode.INVALID_ARGS;
    }
    if (rulePaths.isEmpty()) {
      CliPrinter.println("No rule files supplied. Use rules=path/to/file.yaml");
      return ExitCode.INVALID_ARGS;
    }
    Path samplesDir = resolveSamplesDir(options.getOrDefault("samples", "dev/samples/http"));
    if (!Files.isDirectory(samplesDir)) {
      CliPrinter.println("Samples directory not found: " + samplesDir);
      return ExitCode.INVALID_ARGS;
    }

    try {
      UserEventRuleSetProvider provider = new UserEventRuleSetProvider();
      CompiledRuleSet ruleSet = provider.load(rulePaths);
      UserEventRuleEngine engine = new UserEventRuleEngine(ruleSet);
      if (!engine.hasRules()) {
        CliPrinter.println("Loaded rule files contained no active rules.");
        return ExitCode.SUCCESS;
      }
      SessionResolver sessionResolver = new SessionResolver();
      List<Sample> samples = loadSamples(samplesDir);
      if (samples.isEmpty()) {
        CliPrinter.println("No HTTP samples found in " + samplesDir);
        return ExitCode.SUCCESS;
      }
      AtomicInteger totalMatches = new AtomicInteger();
      AtomicInteger totalSamples = new AtomicInteger();
      for (Sample sample : samples) {
        totalSamples.incrementAndGet();
        HttpExchangeContext exchange = HttpExchangeBuilder.from(sample.pair()).orElse(null);
        if (exchange == null) {
          CliPrinter.println(SAMPLE_PREFIX + sample.name() + ": unable to parse HTTP request/response");
          continue;
        }
        List<RuleMatchResult> matches = engine.evaluate(exchange);
        if (matches.isEmpty()) {
          CliPrinter.println(SAMPLE_PREFIX + sample.name() + ": no matching rules");
          continue;
        }
        CliPrinter.println(SAMPLE_PREFIX + sample.name() + ": " + matches.size() + " match(es)");
        for (RuleMatchResult match : matches) {
          UserEvent event = UserEventAssembler.build(engine, exchange, match, sessionResolver);
          totalMatches.incrementAndGet();
          CliPrinter.println(formatEvent(event));
        }
      }
      CliPrinter.println("\nProcessed " + totalSamples.get() + " sample(s); emitted " + totalMatches.get() + " event(s).");
      return ExitCode.SUCCESS;
    } catch (IOException ex) {
      log.error("Failed to run user event dry-run", ex);
      return ExitCode.IO_ERROR;
    }
  }

  /**
   * Formats a matched user event into a single descriptive log line.
   *
   * @param event user event produced by the rule engine
   * @return human-readable summary showing rule id, event type, and key attributes
   */
  private static String formatEvent(UserEvent event) {
    StringBuilder sb = new StringBuilder("  - ")
        .append(event.ruleId())
        .append(" -> ")
        .append(event.eventType())
        .append(" user=")
        .append(event.userId() == null ? "<none>" : event.userId())
        .append(" session=")
        .append(event.sessionId())
        .append(" auth=")
        .append(event.authState());
    if (event.traceId() != null) {
      sb.append(" traceId=").append(event.traceId());
    }
    if (!event.attributes().isEmpty()) {
      sb.append(" attributes=").append(event.attributes());
    }
    return sb.toString();
  }

  /**
   * Expands the supplied rules argument into an ordered list of files to load.
   *
   * <p>When no explicit value is provided, the method falls back to the default RADAR
   * configuration locations. Any missing file triggers an {@link IllegalArgumentException} so the
   * CLI can report the issue to the operator.
   *
   * @param raw raw argument value extracted from the CLI key-value pairs
   * @return list of existing rule files ready for compilation
   */
  private static List<Path> resolveRulePaths(String raw) {
    List<Path> paths = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      Path defaultPath = Path.of("config", "user-events.yaml");
      if (Files.exists(defaultPath)) {
        paths.add(defaultPath);
      } else {
        Path example = Path.of("config", "user-events.example.yaml");
        if (Files.exists(example)) {
          paths.add(example);
        }
      }
    } else {
      String[] tokens = raw.split("[,;]");
      for (String token : tokens) {
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        Path path = Path.of(trimmed);
        if (!Files.exists(path)) {
          throw new IllegalArgumentException("Rule file not found: " + path);
        }
        paths.add(path);
      }
    }
    return paths;
  }

  /**
   * Resolves the directory holding sample HTTP request/response pairs.
   *
   * @param raw argument value pointing to the desired directory
   * @return existing directory path, absolute when the supplied value does not resolve directly
   */
  private static Path resolveSamplesDir(String raw) {
    Path path = Path.of(raw);
    if (Files.isDirectory(path)) {
      return path;
    }
    return path.toAbsolutePath();
  }

  /**
   * Reads sample files from disk and pairs matching request/response documents.
   *
   * @param directory directory containing <name>-request.txt and <name>-response.txt resources
   * @return ordered list of samples; responses are optional when a file is missing
   * @throws IOException when the sample directory cannot be traversed
   */
  private static List<Sample> loadSamples(Path directory) throws IOException {
    Map<String, Path> requests = new TreeMap<>();
    Map<String, Path> responses = new TreeMap<>();
    try (var stream = Files.list(directory)) {
      stream.forEach(path -> {
        Path fileName = path.getFileName();
        if (fileName == null) {
          return;
        }
        String name = fileName.toString();
        if (name.endsWith("-request.txt")) {
          requests.put(name.substring(0, name.length() - "-request.txt".length()), path);
        } else if (name.endsWith("-response.txt")) {
          responses.put(name.substring(0, name.length() - "-response.txt".length()), path);
        }
      });
    }
    List<Sample> samples = new ArrayList<>();
    for (Map.Entry<String, Path> entry : requests.entrySet()) {
      String name = entry.getKey();
      Path requestPath = entry.getValue();
      Path responsePath = responses.get(name);
      samples.add(new Sample(name, buildPair(requestPath, responsePath)));
    }
    return samples;
  }

  /**
   * Constructs a synthetic message pair used by the assembler to mimic live traffic.
   *
   * <p>The helper reuses a loopback five-tuple because samples originate from disk and not the
   * network capture pipeline.
   *
   * @param requestPath path to the HTTP request payload snippet
   * @param responsePath optional path to the HTTP response payload snippet
   * @return message pair wrapping byte streams ready for rule evaluation
   */
  private static MessagePair buildPair(Path requestPath, Path responsePath) {
    try {
      byte[] requestBytes = Files.readAllBytes(requestPath);
      FiveTuple flow = new FiveTuple("127.0.0.1", 40000, "127.0.0.2", 443, "TCP");
      ByteStream requestStream = new ByteStream(flow, true, requestBytes, 1_000L);
      MessageEvent requestEvent = new MessageEvent(ProtocolId.HTTP, MessageType.REQUEST, requestStream, null);
      MessageEvent responseEvent = null;
      if (responsePath != null && Files.exists(responsePath)) {
        byte[] responseBytes = Files.readAllBytes(responsePath);
        ByteStream responseStream = new ByteStream(flow, false, responseBytes, 2_000L);
        responseEvent = new MessageEvent(ProtocolId.HTTP, MessageType.RESPONSE, responseStream, null);
      }
      return new MessagePair(requestEvent, responseEvent);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read sample files", ex);
    }
  }

  /**
   * Pairing of a logical sample identifier with the reconstructed message pair.
   */
  private record Sample(String name, MessagePair pair) {}
}
