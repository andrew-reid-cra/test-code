package ca.gc.cra.radar.infrastructure.protocol.tn3270;

final class Tn3270Signature {
  private Tn3270Signature() {}

  static boolean looksLikeTn3270(byte[] peek) {
    if (peek == null) return false;
    for (byte b : peek) {
      if ((b & 0xFF) == 0xFF) { // Telnet IAC
        return true;
      }
    }
    return false;
  }
}


