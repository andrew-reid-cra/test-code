package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

/** Minimal sink contract so any writer can be plugged in. */
public interface HttpEventSink {
  boolean offer(HttpMessage m);
}


