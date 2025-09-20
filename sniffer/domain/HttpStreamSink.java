package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

public interface HttpStreamSink {
  interface HttpStream {}
  SegmentSink.StreamHandle begin(SegmentSink.Meta meta, byte[] headers);
  void append(HttpStream handle, byte[] data, int off, int len);
  void end(HttpStream handle, long tsLast);
}


