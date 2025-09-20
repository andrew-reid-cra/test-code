package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

import java.nio.charset.StandardCharsets;

public final class HttpPrinter {
  private HttpPrinter(){}
  public static String firstLineAndMaybeHeaders(byte[] p, int start, int end, boolean headers){
    int max = Math.min(end, start + (headers ? 64*1024 : 4096));
    int i = start;
    while (i < max && p[i] != '\n') i++;
    int lineEnd = i, trimEnd = (lineEnd>start && p[lineEnd-1]=='\r') ? lineEnd-1 : lineEnd;
    var sb = new StringBuilder();
    sb.append(new String(p, start, Math.max(0, trimEnd-start), StandardCharsets.ISO_8859_1)).append('\n');
    if (!headers) return sb.toString();

    sb.append("────────────────────────────────────────────────────────\n");
    int crlf = 0; i = lineEnd + 1;
    for (; i < max; i++){
      byte b = p[i];
      sb.append((char)(b & 0xFF));
      if (b=='\n') { if (++crlf>=2) break; }
      else if (b!='\r') { crlf = 0; }
    }
    sb.append("────────────────────────────────────────────────────────\n");
    return sb.toString();
  }
}


