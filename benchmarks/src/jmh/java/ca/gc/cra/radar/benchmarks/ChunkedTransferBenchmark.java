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
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
public class ChunkedTransferBenchmark {

  @State(Scope.Thread)
  public static class ChunkState {
    private HttpMessageReconstructor reconstructor;
    private ByteStream headersSlice;
    private ByteStream bodySlice;

    @Setup
    public void setup() {
      reconstructor = new HttpMessageReconstructor(ClockPort.SYSTEM, MetricsPort.NO_OP);
      reconstructor.onStart();
      String headersText =
          String.join("\r\n",
              "POST /bulk HTTP/1.1",
              "Host: example",
              "Transfer-Encoding: chunked",
              "User-Agent: radar",
              "")
              + "\r\n";
      byte[] headers = headersText.getBytes(StandardCharsets.US_ASCII);
      byte[] chunk = "6\r\nchunk!\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
      FiveTuple flow = new FiveTuple("10.0.0.1", 4444, "10.0.0.2", 80, "TCP");
      headersSlice = new ByteStream(flow, true, headers, System.nanoTime());
      bodySlice = new ByteStream(flow, true, chunk, System.nanoTime());
    }
  }

  @Benchmark
  public List<MessageEvent> parseChunked(ChunkState state) {
    state.reconstructor.onStart();
    state.reconstructor.onBytes(state.headersSlice);
    return state.reconstructor.onBytes(state.bodySlice);
  }
}
