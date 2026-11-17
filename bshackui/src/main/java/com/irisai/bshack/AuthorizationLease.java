package com.irisai.bshack;
import org.json.JSONObject;
import java.io.IOException;
/** Strict wire validation shared by the Android client and instrumentation checks. */
public final class AuthorizationLease {
 private static long integer(JSONObject j,String key)throws Exception{
  Object v=j.get(key);if(!(v instanceof Integer)&&!(v instanceof Long))throw new IOException("Invalid integer: "+key);
  long n=((Number)v).longValue();if(n<0||n>9007199254740991L)throw new IOException("Invalid range: "+key);return n;
 }
 public static long millis(String raw)throws Exception{
  JSONObject j=new JSONObject(raw);Object active=j.get("active");if(!(active instanceof Boolean))throw new IOException("Invalid active field");
  if(!(Boolean)active)return 0;
  long seconds=integer(j,"leaseSeconds"),server=integer(j,"serverTime");
  if(seconds<1||seconds>300)throw new IOException("Invalid lease");
  if(!Boolean.TRUE.equals(j.opt("permanent"))){long expiry=integer(j,"expiresAt");if(expiry<=server||expiry-server<seconds*1000L)throw new IOException("Invalid expiration");}
  return seconds*1000L;
 }
}
