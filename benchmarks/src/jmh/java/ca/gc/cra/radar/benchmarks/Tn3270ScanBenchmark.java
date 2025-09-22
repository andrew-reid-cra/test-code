
package ca.gc.cra.radar.benchmarks;

import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270MessageReconstructor;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
public class Tn3270ScanBenchmark {

  @State(Scope.Thread)
  public static class TnState {
    private Tn3270MessageReconstructor reconstructor;
    private ByteStream recordSlice;

    @Setup
    public void setup() {
      reconstructor = new Tn3270MessageReconstructor(ClockPort.SYSTEM, MetricsPort.NO_OP);
      reconstructor.onStart();
      byte[] payload = new byte[256];
      ThreadLocalRandom.current().nextBytes(payload);
      // terminate with IAC EOR sequence
      payload[payload.length - 2] = (byte) 0xFF;
      payload[payload.length - 1] = (byte) 0xEF;
      FiveTuple flow = new FiveTuple("192.168.0.10", 23, "192.168.0.20", 3270, "TCP");
      recordSlice = new ByteStream(flow, false, payload, System.nanoTime());
    }
  }

  @Benchmark
  public List<MessageEvent> parseRecord(TnState state) {
    state.reconstructor.onStart();
    return state.reconstructor.onBytes(state.recordSlice);
  }
}
