from pathlib import Path
path = Path(r"src/main/java/ca/gc/cra/radar/config/CaptureConfig.java")
text = path.read_text()
start = text.index("public record CaptureConfig(")
end = text.index("  /**\n   * Provides default capture settings", start)
new_block = '''public record CaptureConfig(
    String iface,
    Path pcapFile,
    String filter,
    boolean customBpfEnabled,
    int snaplen,
    int bufferBytes,
    int timeoutMillis,
    boolean promiscuous,
    boolean immediate,
    Path outputDirectory,
    String fileBase,
    int rollMiB,
    Path httpOutputDirectory,
    Path tn3270OutputDirectory,
    IoMode ioMode,
    String kafkaBootstrap,
    String kafkaTopicSegments,
    int persistenceWorkers,
    int persistenceQueueCapacity,
    PersistenceQueueType persistenceQueueType) {

  private static final String DEFAULT_SAFE_BPF = "tcp";
  private static final int DEFAULT_SNAPLEN = 65_535;
  private static final int DEFAULT_BUFFER_MIB = 256;
  private static final int DEFAULT_TIMEOUT_MILLIS = 1_000;
  private static final int DEFAULT_ROLL_MIB = 512;
  private static final int MIN_SNAPLEN = 64;
  private static final int MAX_SNAPLEN = 262_144;
  private static final int MIN_BUFFER_MIB = 4;
  private static final int MAX_BUFFER_MIB = 4_096;
  private static final int MIN_TIMEOUT_MILLIS = 0;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MIN_ROLL_MIB = 8;
  private static final int MAX_ROLL_MIB = 10_240;
  private static final int MAX_BPF_LENGTH = 1_024;
  private static final int MIN_PERSIST_WORKERS = 1;
  private static final int MAX_PERSIST_WORKERS = 32;
  private static final int MAX_QUEUE_CAPACITY = 65_536;

  /**
   * Validates capture configuration values.
   *
   * @since RADAR 0.1-doc
   */
  public CaptureConfig {
    boolean offline = pcapFile != null;
    if (offline) {
      pcapFile = normalizePath("pcapFile", pcapFile);
    }
    if (!offline) {
      iface = Strings.requireNonBlank("iface", iface);
    } else if (iface == null || iface.isBlank()) {
      iface = "pcap:" + pcapFile.getFileName();
    } else {
      iface = Strings.requireNonBlank("iface", iface);
    }
    filter = Strings.requirePrintableAscii("bpf", filter, MAX_BPF_LENGTH);
    outputDirectory = normalizePath("outputDirectory", outputDirectory);
    httpOutputDirectory = normalizePath("httpOutputDirectory", httpOutputDirectory);
    tn3270OutputDirectory = normalizePath("tn3270OutputDirectory", tn3270OutputDirectory);
    fileBase = Strings.requireNonBlank("fileBase", fileBase);
    Numbers.requireRange("snaplen", snaplen, MIN_SNAPLEN, MAX_SNAPLEN);
    Numbers.requireRange(
        "bufferBytes",
        bufferBytes,
        MIN_BUFFER_MIB * 1_024L * 1_024L,
        MAX_BUFFER_MIB * 1_024L * 1_024L);
    Numbers.requireRange("timeoutMillis", timeoutMillis, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
    Numbers.requireRange("rollMiB", rollMiB, MIN_ROLL_MIB, MAX_ROLL_MIB);
    Numbers.requireRange("persistenceWorkers", persistenceWorkers, MIN_PERSIST_WORKERS, MAX_PERSIST_WORKERS);
    Numbers.requireRange(
        "persistenceQueueCapacity",
        persistenceQueueCapacity,
        persistenceWorkers,
        MAX_QUEUE_CAPACITY);
    ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    persistenceQueueType = Objects.requireNonNullElse(persistenceQueueType, PersistenceQueueType.ARRAY);
    if (kafkaBootstrap != null && !kafkaBootstrap.isBlank()) {
      kafkaBootstrap = Net.validateHostPort(kafkaBootstrap);
    } else {
      kafkaBootstrap = null;
    }
    if (kafkaTopicSegments != null) {
      kafkaTopicSegments = Strings.sanitizeTopic("kafkaTopicSegments", kafkaTopicSegments);
    }
    if (ioMode == IoMode.KAFKA) {
      if (kafkaBootstrap == null) {
        throw new IllegalArgumentException("kafkaBootstrap is required when ioMode=KAFKA");
      }
      if (kafkaTopicSegments == null || kafkaTopicSegments.isBlank()) {
        throw new IllegalArgumentException("kafkaTopicSegments is required when ioMode=KAFKA");
      }
    }
  }

'''
text = text[:start] + new_block + text[end:]
path.write_text(text)
