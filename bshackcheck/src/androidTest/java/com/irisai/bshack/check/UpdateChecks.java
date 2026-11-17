package com.irisai.bshack.check;
import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class UpdateChecks {
 private static void require(boolean value,String label,List<String> checks){if(!value)throw new AssertionError(label);checks.add(label);}
 private static byte[] read(Context c,String name)throws Exception{try(InputStream in=c.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
 private static void denied(Runnable action,String label,List<String> checks){try{action.run();}catch(RuntimeException e){checks.add(label);return;}throw new AssertionError(label);}
 private static Object invoke(Method m,Object target,Object...args){try{return m.invoke(target,args);}catch(Exception e){throw new RuntimeException(e);}}
 static void run(Context c,ClassLoader loader,List<String> checks)throws Exception{
  Class<?> lease=loader.loadClass("com.irisai.bshack.AuthorizationLease");Method accept=lease.getMethod("millis",String.class);
  require((Long)accept.invoke(null,"{\"active\":true,\"permanent\":true,\"serverTime\":1000,\"leaseSeconds\":300}")==300000L,"authorization accepts bounded permanent lease",checks);
  require((Long)accept.invoke(null,"{\"active\":false,\"status\":\"revoked\"}")==0L,"revocation clears lease",checks);
  require((Long)accept.invoke(null,"{\"active\":true,\"permanent\":false,\"serverTime\":1000,\"expiresAt\":3000,\"leaseSeconds\":2}")==2000L,"fixed expiry lease is bounded",checks);
  for(String raw:new String[]{"{\"active\":\"true\"}","{\"active\":true,\"permanent\":true,\"serverTime\":1000,\"leaseSeconds\":301}","{\"active\":true,\"permanent\":true,\"serverTime\":1000,\"leaseSeconds\":1.5}","{\"active\":true,\"serverTime\":1000,\"expiresAt\":1500,\"leaseSeconds\":1}"})denied(()->invoke(accept,null,raw),"invalid authorization lease rejected",checks);
  Class<?> type=loader.loadClass("com.irisai.bshack.update.PatchStore");Class<?> manifestType=loader.loadClass("com.irisai.bshack.update.PatchStore$Manifest");
  Method verify=type.getMethod("verifyManifest",byte[].class,byte[].class),install=type.getMethod("install",manifestType,byte[].class,long.class),select=type.getMethod("select"),healthy=type.getMethod("healthy",long.class);
  byte[] manifest=read(c,"manifest.json"),signature=read(c,"manifest.sig"),zip=read(c,"patch.zip");Object m=verify.invoke(null,manifest,signature);
  require(m!=null,"real pinned-key signature accepted",checks);
  byte[] wrong=manifest.clone();wrong[10]^=1;denied(()->invoke(verify,null,wrong,signature),"tampered manifest rejected",checks);
  byte[] wrongSig=signature.clone();wrongSig[0]^=1;denied(()->invoke(verify,null,manifest,wrongSig),"wrong signature rejected",checks);
  File folder=new File(c.getCacheDir(),"patch-tests");type.getMethod("remove",File.class).invoke(null,folder);
  Object store=type.getConstructor(File.class).newInstance(folder);
  byte[] damaged=zip.clone();damaged[damaged.length/2]^=1;
  denied(()->invoke(install,store,m,damaged,22000L),"damaged download leaves installation untouched",checks);
  require(!new File(folder,"active").exists(),"failed update creates no active marker",checks);
  install.invoke(store,m,zip,22000L);
  JSONObject boot=(JSONObject)select.invoke(store);require(boot.getLong("version")==22001,"signed patch selected after atomic install",checks);
  healthy.invoke(store,22001L);boot=(JSONObject)select.invoke(store);require(boot.getLong("version")==22001,"healthy patch survives a subsequent boot",checks);
  boot=(JSONObject)select.invoke(store);require(boot.getLong("version")==22000&&!boot.getString("recovery").isEmpty(),"unfinished patch startup rolls back to embedded code",checks);
  require((Long)type.getMethod("blockedVersion").invoke(store)==22001L,"failed patch is not offered repeatedly",checks);
  denied(()->invoke(install,store,m,zip,22001L),"same-version and downgrade install rejected",checks);
  install.invoke(store,m,zip,22000L);healthy.invoke(store,22001L);
  try(FileOutputStream out=new FileOutputStream(new File(folder,"v22001/panel.dex"))){out.write(new byte[]{1,2,3});}
  boot=(JSONObject)select.invoke(store);require(boot.getLong("version")==22000,"modified cached DEX is rejected before execution",checks);
  type.getMethod("remove",File.class).invoke(null,folder);
 }
}
