package sniffer.adapters.libpcap;

import jnr.ffi.Pointer;
import sniffer.adapters.libpcap.cstruct.BpfProgramStruct;

public interface JnrLibpcap {
  JnrLibpcap INSTANCE = jnr.ffi.LibraryLoader.create(JnrLibpcap.class).load("pcap");
  Pointer pcap_create(String source, Pointer errbuf);
  int     pcap_set_snaplen(Pointer p, int snaplen);
  int     pcap_set_promisc(Pointer p, int promisc);
  int     pcap_set_timeout(Pointer p, int to_ms);
  int     pcap_set_buffer_size(Pointer p, int bytes);
  int     pcap_set_immediate_mode(Pointer p, int on);
  int     pcap_activate(Pointer p);
  int     pcap_compile(Pointer p, BpfProgramStruct prog, String filter, int optimize, int netmask);
  int     pcap_setfilter(Pointer p, BpfProgramStruct prog);
  void    pcap_freecode(BpfProgramStruct prog);
  int     pcap_next_ex(Pointer p, Pointer hdr_pp, Pointer data_pp);
  String  pcap_geterr(Pointer p);
  void    pcap_close(Pointer p);
  String  pcap_lib_version();
}
