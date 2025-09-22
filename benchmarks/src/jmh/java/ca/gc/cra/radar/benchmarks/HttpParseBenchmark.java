package ca.gc.cra.radar.benchmarks;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.infrastructure.protocol.http.HttpMessageReconstructor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
public class HttpParseBenchmark {

  @State(Scope.Thread)
  public static class HttpState {
    private HttpMessageReconstructor reconstructor;
    private ByteStream requestSlice;

    @Setup(Level.Iteration)
    public void setup() {
      reconstructor = new HttpMessageReconstructor(ClockPort.SYSTEM, MetricsPort.NO_OP);
      String headersText =
          String.join("\r\n",
              "POST /batch HTTP/1.1",
              "Host: example",
              "User-Agent: radar",
              "Content-Type: application/json",
              "Content-Length: 24",
              "")
              + "\r\n";
      byte[] headers = headersText.getBytes(StandardCharsets.US_ASCII);
      byte[] body = "{\"message\":\"payload\"}".getBytes(StandardCharsets.US_ASCII);
      byte[] payload = new byte[headers.length + body.length];
      System.arraycopy(headers, 0, payload, 0, headers.length);
      System.arraycopy(body, 0, payload, headers.length, body.length);
      FiveTuple flow = new FiveTuple("10.0.0.1", 1234, "10.0.0.2", 80, "TCP");
      requestSlice = new ByteStream(flow, true, payload, System.nanoTime());
    }

    @Setup(Level.Invocation)
    public void reset() {
      reconstructor.onStart();
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
      reconstructor.onClose();
    }
  }

  @Benchmark
  public List<MessageEvent> parseRequest(HttpState state) {
    return state.reconstructor.onBytes(state.requestSlice);
  }
}

