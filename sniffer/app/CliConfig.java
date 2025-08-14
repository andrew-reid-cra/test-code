package sniffer.app;

final class CliConfig {
  final String iface, bpf; 
  final int bufMb, snap, timeoutMs; 
  final boolean verbose, headers;
  CliConfig(String[] args){
    String i=null,f=null; int mb=1024,sn=65535,to=1; boolean v=false,h=false;
    for (String a: args){
      String[] kv=a.split("=",2); String k=kv[0]; String val=kv.length>1?kv[1]:"";
      switch(k){
        case "iface" -> i=val;
        case "bufmb" -> mb=Integer.parseInt(val);
        case "snap"  -> sn=Integer.parseInt(val);
        case "timeout" -> to=Integer.parseInt(val);
        case "bpf"   -> f=val;
        case "v"     -> v=Boolean.parseBoolean(val);
        case "headers" -> h=Boolean.parseBoolean(val);
      }
    }
    iface=i; bpf=f; bufMb=mb; snap=sn; timeoutMs=to; verbose=v; headers=h;
  }
}
