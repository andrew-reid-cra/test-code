package sniffer.spi;

public interface Pcap {
  PcapHandle openLive(String iface, int snapLen, boolean promiscuous,
                      int timeoutMs, int bufferBytes, boolean immediate) throws PcapException;

  String libVersion();

  interface PacketCallback {
    void onPacket(long tsMicros, byte[] data, int capLen);
  }
}


