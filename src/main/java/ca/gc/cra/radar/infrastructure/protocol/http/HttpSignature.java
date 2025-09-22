package ca.gc.cra.radar.infrastructure.protocol.http;

import ca.gc.cra.radar.domain.util.Utf8;

final class HttpSignature {
  private HttpSignature() {}

  static boolean looksLikeHttp(byte[] peek) {
    if (peek == null || peek.length == 0) return false;
    String prefix = Utf8.decode(peek, 0, Math.min(peek.length, 16));
    return prefix.startsWith("GET ")
        || prefix.startsWith("POST ")
        || prefix.startsWith("PUT ")
        || prefix.startsWith("HEAD ")
        || prefix.startsWith("DELETE ")
        || prefix.startsWith("OPTIONS ")
        || prefix.startsWith("TRACE ")
        || prefix.startsWith("PATCH ")
        || prefix.startsWith("HTTP/");
  }
}
