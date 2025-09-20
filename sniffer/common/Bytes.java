package sniffer.common;

public final class Bytes {
  private Bytes(){}
  public static int u8(byte[] a, int off){ return a[off] & 0xFF; }
  public static int u16be(byte[] a, int off){ return (u8(a,off)<<8) | u8(a,off+1); }
  public static int u32be(byte[] a, int off){
    return (u8(a,off)<<24)|(u8(a,off+1)<<16)|(u8(a,off+2)<<8)|u8(a,off+3);
  }
}


