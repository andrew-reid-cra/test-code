package ca.gc.cra.radar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultsForModeTest {

  @Test
  void captureDefaultsMirrorCaptureConfig() {
    Map<String, String> defaults = DefaultsForMode.asFlatMap("capture");
    CaptureConfig capture = CaptureConfig.defaults();

    assertEquals(capture.iface(), defaults.get("iface"));
    assertEquals(Integer.toString(capture.snaplen()), defaults.get("snaplen"));
    assertEquals(
        Integer.toString(capture.bufferBytes() / (1024 * 1024)),
        defaults.get("bufmb"));
    assertEquals(capture.outputDirectory().toString(), defaults.get("out"));
    assertEquals(capture.httpOutputDirectory().toString(), defaults.get("httpOut"));
    assertEquals(capture.tn3270OutputDirectory().toString(), defaults.get("tnOut"));
    assertEquals(capture.kafkaTopicSegments(), defaults.get("kafkaTopicSegments"));
    assertEquals(Boolean.toString(capture.tn3270EmitScreenRenders()),
        defaults.get("tn3270.emitScreenRenders"));
    assertEquals("false", defaults.get("enableBpf"));
    assertEquals("otlp", defaults.get("metricsExporter"));
    assertEquals("tcp", defaults.get("protocolDefaultFilter.GENERIC"));
    assertEquals("tcp and (port 23 or port 992)", defaults.get("protocolDefaultFilter.TN3270"));
  }

  @Test
  void liveDefaultsReuseCaptureAndExposeProtocolToggles() {
    Map<String, String> live = DefaultsForMode.asFlatMap("live");
    Map<String, String> capture = DefaultsForMode.asFlatMap("capture");

    assertEquals(capture.get("iface"), live.get("iface"));
    assertEquals("true", live.get("httpEnabled"));
    assertEquals("false", live.get("tnEnabled"));
    assertEquals("tcp", live.get("protocolDefaultFilter.GENERIC"));
    assertEquals("tcp and (port 23 or port 992)", live.get("protocolDefaultFilter.TN3270"));
  }

  @Test
  void assembleDefaultsMatchRecordDefaults() {
    Map<String, String> defaults = DefaultsForMode.asFlatMap("assemble");
    AssembleConfig assemble = AssembleConfig.defaults();

    assertEquals(assemble.ioMode().name(), defaults.get("ioMode"));
    assertEquals(assemble.kafkaSegmentsTopic(), defaults.get("kafkaSegmentsTopic"));
    assertEquals(assemble.kafkaHttpPairsTopic(), defaults.get("kafkaHttpPairsTopic"));
    assertEquals(assemble.kafkaTnPairsTopic(), defaults.get("kafkaTnPairsTopic"));
    assertEquals(assemble.inputDirectory().toString(), defaults.get("in"));
    assertEquals(assemble.outputDirectory().toString(), defaults.get("out"));
    assertEquals(Boolean.toString(assemble.httpEnabled()), defaults.get("httpEnabled"));
    assertEquals(Boolean.toString(assemble.tnEnabled()), defaults.get("tnEnabled"));
  }

  @Test
  void posterDefaultsPointToLocalPipelines() {
    Map<String, String> defaults = DefaultsForMode.asFlatMap("poster");
    Path base = Path.of(System.getProperty("user.home", "."), ".radar", "out");

    assertEquals("FILE", defaults.get("ioMode"));
    assertEquals("FILE", defaults.get("posterOutMode"));
    assertEquals(base.resolve("assemble").resolve("http").toString(), defaults.get("httpIn"));
    assertEquals(base.resolve("poster").resolve("http").toString(), defaults.get("httpOut"));
    assertEquals("none", defaults.get("decode"));
    assertTrue(defaults.containsKey("metricsExporter"));
  }
}

