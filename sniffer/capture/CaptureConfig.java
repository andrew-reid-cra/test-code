package sniffer.capture;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class CaptureConfig {
  final String iface;
  final String bpf;
  final int snap;
  final int bufBytes;
  final int timeoutMs;
  final boolean promisc;
  final boolean immediate;
  final Path outDir;
  final String fileBase;
  final int rollMiB;

  static CaptureConfig fromArgs(String[] args) {
    Map<String,String> kv = new HashMap<>();
    for (String a: args){ int i=a.indexOf('='); if (i>0) kv.put(a.substring(0,i), a.substring(i+1)); }
    String iface = kv.getOrDefault("iface", "eth0");
    String bpf   = kv.getOrDefault("bpf", "");
    int snap     = parseInt(kv.getOrDefault("snap","65535"), 65535);
    int bufmb    = parseInt(kv.getOrDefault("bufmb","256"), 256);
    int timeout  = parseInt(kv.getOrDefault("timeout","1"), 1);
    boolean prom = Boolean.parseBoolean(kv.getOrDefault("promisc","true"));
    boolean imm  = Boolean.parseBoolean(kv.getOrDefault("immediate","true"));
    String out   = kv.getOrDefault("out","./cap-out");
    String base  = kv.getOrDefault("base","cap");
    int roll     = parseInt(kv.getOrDefault("rollMiB","512"), 512);

    return new CaptureConfig(iface,bpf,snap,bufmb*1024*1024,timeout,prom,imm,
        java.nio.file.Paths.get(out), base, roll);
  }

  private CaptureConfig(String iface, String bpf, int snap, int bufBytes, int timeoutMs,
                        boolean promisc, boolean immediate, Path outDir, String base, int rollMiB) {
    this.iface=iface; this.bpf=bpf; this.snap=snap; this.bufBytes=bufBytes;
    this.timeoutMs=timeoutMs; this.promisc=promisc; this.immediate=immediate;
    this.outDir=outDir; this.fileBase=base; this.rollMiB=rollMiB;
  }

  private static int parseInt(String s, int def){ try { return Integer.parseInt(s); } catch (Exception e){ return def; } }
}
