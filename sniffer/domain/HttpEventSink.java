package sniffer.domain;

/** Minimal sink contract so any writer can be plugged in. */
public interface HttpEventSink {
  boolean offer(HttpMessage m);
}
