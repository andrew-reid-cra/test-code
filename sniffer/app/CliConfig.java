package sniffer.app;

final class CliConfig {
  final String iface;
  final String bpf;
  final int bufMb;
  final int snap;
  final int timeoutMs;
  final boolean verbose;
  final boolean headers;
  final String httpOutDir;
  final String tnOutDir;
  final boolean enableHttp;
  final boolean enableTn;

  CliConfig(String[] args){
    String i = null, f = null;
    int mb = 1024, sn = 65535, to = 1;
    boolean v = false, h = false;
    String httpOut = "out/http";
    String tnOut = "out/tn3270";
    Boolean httpEnable = null;
    Boolean tnEnable = null;

    for (String a : args){
      String[] kv = a.split("=", 2);
      String k = kv[0];
      String val = kv.length > 1 ? kv[1] : "";
      switch (k) {
        case "iface" -> i = val;
        case "bufmb" -> mb = Integer.parseInt(val);
        case "snap" -> sn = Integer.parseInt(val);
        case "timeout" -> to = Integer.parseInt(val);
        case "bpf" -> f = val;
        case "v" -> v = Boolean.parseBoolean(val);
        case "headers" -> h = Boolean.parseBoolean(val);
        case "httpOut" -> httpOut = emptyToNull(val);
        case "tnOut" -> tnOut = emptyToNull(val);
        case "httpEnabled", "http" -> httpEnable = Boolean.parseBoolean(val);
        case "tnEnabled", "tn3270" -> tnEnable = Boolean.parseBoolean(val);
      }
    }

    boolean httpOn = (httpEnable != null) ? httpEnable : httpOut != null;
    boolean tnOn = (tnEnable != null) ? tnEnable : tnOut != null;

    if (!httpOn) httpOut = null;
    if (!tnOn) tnOut = null;

    iface = i;
    bpf = f;
    bufMb = mb;
    snap = sn;
    timeoutMs = to;
    verbose = v;
    headers = h;
    httpOutDir = httpOut;
    tnOutDir = tnOut;
    enableHttp = httpOn;
    enableTn = tnOn;
  }

  private static String emptyToNull(String s) {
    if (s == null) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
