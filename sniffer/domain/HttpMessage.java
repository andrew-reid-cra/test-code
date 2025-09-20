// sniffer/domain/HttpMessage.java
package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

public sealed interface HttpMessage permits HttpRequest, HttpResponse {
  String id(); String sessionId(); long tsFirstMicros(); long tsLastMicros();
  String srcIp(); int srcPort(); String dstIp(); int dstPort();
  byte[] rawStartLineAndHeaders(); byte[] rawBody();
  default String kind(){ return (this instanceof HttpRequest) ? "REQ" : "RSP"; }
}
final class HttpRequest implements HttpMessage {
  public final String id, sid, src, dst; public final int sp, dp; public final long t0,t1; public final byte[] head, body;
  public HttpRequest(String id, String sid, long t0, long t1, String src, int sp, String dst, int dp, byte[] head, byte[] body){
    this.id=id; this.sid=sid; this.t0=t0; this.t1=t1; this.src=src; this.sp=sp; this.dst=dst; this.dp=dp; this.head=head; this.body=body;
  }
  public String id(){return id;} public String sessionId(){return sid;} public long tsFirstMicros(){return t0;} public long tsLastMicros(){return t1;}
  public String srcIp(){return src;} public int srcPort(){return sp;} public String dstIp(){return dst;} public int dstPort(){return dp;}
  public byte[] rawStartLineAndHeaders(){return head;} public byte[] rawBody(){return body;}
}
final class HttpResponse implements HttpMessage {
  public final String id, sid, src, dst; public final int sp, dp; public final long t0,t1; public final byte[] head, body;
  public HttpResponse(String id, String sid, long t0, long t1, String src, int sp, String dst, int dp, byte[] head, byte[] body){
    this.id=id; this.sid=sid; this.t0=t0; this.t1=t1; this.src=src; this.sp=sp; this.dst=dst; this.dp=dp; this.head=head; this.body=body;
  }
  public String id(){return id;} public String sessionId(){return sid;} public long tsFirstMicros(){return t0;} public long tsLastMicros(){return t1;}
  public String srcIp(){return src;} public int srcPort(){return sp;} public String dstIp(){return dst;} public int dstPort(){return dp;}
  public byte[] rawStartLineAndHeaders(){return head;} public byte[] rawBody(){return body;}
}



