package sniffer.poster;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small helpers including tolerant JSONL key scanning (no external deps). */
final class Util {
  private Util(){}

  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
  static String tsNow(){ return TS.format(Instant.now()); }

  // VERY TOLERANT extractors: looks for "key": value with either "string" or number
  static String getString(String json, String key){
    int i = indexOfKey(json, key); if (i < 0) return null;
    i = skipWs(json, i);
    if (i < json.length() && json.charAt(i) == '"'){
      int j = i+1;
      StringBuilder sb = new StringBuilder(64);
      while (j < json.length()){
        char c = json.charAt(j++);
        if (c == '\\'){ if (j<json.length()) { char n=json.charAt(j++); sb.append(n); } }
        else if (c == '"') break;
        else sb.append(c);
      }
      return sb.toString();
    } else {
      // bare token until , or }
      int j = i;
      while (j < json.length()){
        char c = json.charAt(j);
        if (c==',' || c=='}' || c==' ' || c=='\t' || c=='\r' || c=='\n') break;
        j++;
      }
      String tok = json.substring(i, j).trim();
      if (tok.isEmpty() || "null".equals(tok)) return null;
      return tok;
    }
  }

  static long getLong(String json, String key, long def){
    int i = indexOfKey(json, key); if (i < 0) return def;
    i = skipWs(json, i);
    int j = i;
    boolean neg=false;
    if (j<json.length() && json.charAt(j)=='-'){ neg=true; j++; }
    long val=0; boolean any=false;
    while (j<json.length()){
      char c = json.charAt(j);
      if (c<'0'||c>'9') break;
      val = val*10 + (c-'0');
      any = true; j++;
    }
    if (!any) return def;
    return neg? -val : val;
  }

  static int indexOfKey(String json, String key){
    // Search for "key":
    String pat = "\"" + key + "\"";
    int at = json.indexOf(pat);
    if (at < 0) return -1;
    at = json.indexOf(':', at + pat.length());
    return (at < 0 ? -1 : at+1);
  }
  static int skipWs(String s, int i){
    while (i<s.length()){
      char c=s.charAt(i);
      if (c==' '||c=='\t'||c=='\r'||c=='\n') i++;
      else break;
    }
    return i;
  }
}


