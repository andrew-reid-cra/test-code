package sniffer.app;

import sniffer.adapters.libpcap.JnrPcapAdapter;
import sniffer.domain.CaptureLoop;
import sniffer.domain.SegmentSink;

import java.nio.file.Paths;

public final class CaptureRunner {
  public static void main(String[] args) throws Exception {
    var cfg = new CliConfig(args);
    if (cfg.iface == null) {
      System.err.println("usage: iface=<nic> [bufmb=1024 snap=65535 timeout=1 headers=false bpf='<expr>' httpOut=out/http tnOut=out/tn3270 httpEnabled=true tnEnabled=true]");
      return;
    }
    var pcap = new JnrPcapAdapter();
    System.out.println("[libpcap] " + pcap.libVersion());
    System.out.printf("Config iface=%s buf=%dMB snap=%d timeout=%dms bpf='%s'%n",
        cfg.iface, cfg.bufMb, cfg.snap, cfg.timeoutMs, cfg.bpf == null ? "" : cfg.bpf);

    SegmentSink httpSink = null;
    SegmentSink tnSink = null;
    try {
      if (cfg.enableHttp && cfg.httpOutDir != null) {
        httpSink = new SegmentSink(SegmentSink.Config.hourlyGiB(Paths.get(cfg.httpOutDir)));
      }
      if (cfg.enableTn && cfg.tnOutDir != null) {
        tnSink = new SegmentSink(SegmentSink.Config.hourlyGiB(Paths.get(cfg.tnOutDir)));
      }

      var loop = new CaptureLoop(pcap, new StdoutSink(), cfg.headers, httpSink, tnSink);
      loop.runForever(cfg.iface, cfg.bufMb, cfg.snap, cfg.timeoutMs, cfg.bpf);
    } finally {
      if (httpSink != null) {
        try { httpSink.close(); } catch (Exception ignore) { }
      }
      if (tnSink != null) {
        try { tnSink.close(); } catch (Exception ignore) { }
      }
    }
  }

  static final class StdoutSink implements CaptureLoop.PacketSink {
    @Override public void onHttpLine(long tsMicros, String text){ System.out.print(text); }
    @Override public void onUdp(long tsMicros, String src, int sport, String dst, int dport){
      System.out.printf("%d TAP %s:%d�+'%s:%d UDP%n", tsMicros, src, sport, dst, dport);
    }
  }
}
