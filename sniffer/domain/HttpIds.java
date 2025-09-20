// sniffer/domain/HttpIds.java
package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class HttpIds {
  private HttpIds(){}
  private static final char[] ENC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  public static String ulid() {
    long time = Instant.now().toEpochMilli();
    long r1 = ThreadLocalRandom.current().nextLong();
    long r2 = ThreadLocalRandom.current().nextLong();
    char[] out = new char[26]; enc48(time,out,0); enc80(r1,r2,out); return new String(out);
  }
  private static void enc48(long v, char[] d, int o){ for(int i=9;i>=0;i--){ d[o+i]=ENC[(int)(v&31)]; v>>>=5; } }
  private static void enc80(long r1,long r2,char[]d){ long a=(r1<<16)|((r2>>>48)&0xFFFFL), b=r2&0x0000FFFFFFFFFFFFL;
    for(int i=25;i>=10;i--){ int idx=(int)((i>=18?b:a)&31); d[i]=ENC[idx]; if(i==18)continue; if(i>18)b>>>=5; else a>>>=5; } }
}



