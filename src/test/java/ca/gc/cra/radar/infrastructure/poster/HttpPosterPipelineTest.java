package ca.gc.cra.radar.infrastructure.poster;

import static org.junit.jupiter.api.Assertions.*;

import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.ProtocolConfig;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class HttpPosterPipelineTest {

  @Test
  void requiresInputDirectory() {
    HttpPosterPipeline pipeline = new HttpPosterPipeline();
    ProtocolConfig config = new ProtocolConfig(Optional.empty(), Optional.empty(), Optional.of(Path.of("unused")), Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> pipeline.process(config, PosterConfig.DecodeMode.NONE, report -> fail("output port should be ignored")));
  }

  @Test
  void requiresOutputDirectory() {
    HttpPosterPipeline pipeline = new HttpPosterPipeline();
    ProtocolConfig config = new ProtocolConfig(Optional.of(Path.of("unused")), Optional.empty(), Optional.empty(), Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> pipeline.process(config, PosterConfig.DecodeMode.NONE, report -> fail("output port should be ignored")));
  }

  @Test
  void forwardsDecodeModeAndIgnoresOutputPort() throws Exception {
    Path input = PosterFixtures.tempDir("http-pipeline-in");
    Path outNone = PosterFixtures.tempDir("http-pipeline-out-none");
    Path outAll = PosterFixtures.tempDir("http-pipeline-out-all");
    try {
      prepareHttpDataset(input);
      HttpPosterPipeline pipeline = new HttpPosterPipeline();
      PosterOutputPortRejecting writesForbidden = new PosterOutputPortRejecting();

      ProtocolConfig noneCfg = new ProtocolConfig(Optional.of(input), Optional.empty(), Optional.of(outNone), Optional.empty());
      pipeline.process(noneCfg, PosterConfig.DecodeMode.NONE, writesForbidden);
      String noneContent = readSinglePoster(outNone);
      assertTrue(noneContent.contains("Transfer-Encoding: chunked"));
      assertTrue(noneContent.contains("Content-Encoding: gzip"));

      ProtocolConfig allCfg = new ProtocolConfig(Optional.of(input), Optional.empty(), Optional.of(outAll), Optional.empty());
      pipeline.process(allCfg, PosterConfig.DecodeMode.ALL, writesForbidden);
      String allContent = readSinglePoster(outAll);
      assertTrue(allContent.contains("Content-Length: 12"));
      assertFalse(allContent.contains("Content-Encoding"));
      assertTrue(allContent.contains("decoded body"));
    } finally {
      PosterFixtures.deleteRecursively(outAll);
      PosterFixtures.deleteRecursively(outNone);
      PosterFixtures.deleteRecursively(input);
    }
  }

  private static void prepareHttpDataset(Path input) throws Exception {
    Files.createDirectories(input);
    Path headers = input.resolve("req-headers.bin");
    Path body = input.resolve("req-body.bin");
    Path rspHeaders = input.resolve("rsp-headers.bin");
    Path rspBody = input.resolve("rsp-body.bin");

    PosterFixtures.write(headers, PosterFixtures.ascii("GET /decode HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"));
    PosterFixtures.write(body, PosterFixtures.ascii("c\r\ndecoded body\r\n0\r\n\r\n"));
    PosterFixtures.write(rspHeaders, PosterFixtures.ascii("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n"));
    PosterFixtures.write(rspBody, PosterFixtures.gzip(PosterFixtures.ascii("decoded body")));

    String reqEntry = PosterFixtures.httpIndexEntry(
        "pipeline",
        "REQ",
        10L,
        11L,
        "src:1",
        "dst:2",
        "GET /decode HTTP/1.1",
        headers.getFileName().toString(),
        Files.size(headers),
        body.getFileName().toString(),
        Files.size(body));
    String rspEntry = PosterFixtures.httpIndexEntry(
        "pipeline",
        "RSP",
        12L,
        13L,
        "dst:2",
        "src:1",
        "HTTP/1.1 200 OK",
        rspHeaders.getFileName().toString(),
        Files.size(rspHeaders),
        rspBody.getFileName().toString(),
        Files.size(rspBody));
    Files.writeString(
        input.resolve("index-000.ndjson"),
        reqEntry + System.lineSeparator() + rspEntry + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }

  private static String readSinglePoster(Path output) throws Exception {
    try (var stream = Files.list(output)) {
      Path poster = stream.findFirst().orElseThrow();
      return Files.readString(poster, StandardCharsets.UTF_8);
    }
  }

  private static final class PosterOutputPortRejecting implements ca.gc.cra.radar.application.port.poster.PosterOutputPort {
    @Override
    public void write(ca.gc.cra.radar.application.port.poster.PosterOutputPort.PosterReport report) {
      fail("File pipelines must not use output port");
    }
  }
}
