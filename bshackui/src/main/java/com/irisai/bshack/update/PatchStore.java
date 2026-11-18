package com.irisai.bshack.update;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

/** Signed, bounded two-file patches. The immutable APK bootstrap revalidates every boot. */
public final class PatchStore {
 public static final int MAX_PATCH=16*1024*1024, MAX_MANIFEST=32768;
 private final File root;
 public PatchStore(Context context){this(new File(context.getFilesDir(),"iris-updates"));}
 public PatchStore(File root){this.root=root;root.mkdirs();}
 public File root(){return root;}
 public static final class Manifest {
  public final long version;
  public final String name,notes,url,sha,engineSha,uiSha;
  public final int bytes;
  public final byte[] raw,signature;
  private Manifest(byte[] raw,byte[] signature)throws Exception{
   JSONObject j=new JSONObject(new String(raw,StandardCharsets.UTF_8));
   if(j.getInt("schema")!=1||!UpdateConfig.BASE.equals(j.getString("base")))throw new IOException("이 게임 버전에 적용할 수 없는 패치입니다.");
   version=j.getLong("version");name=j.getString("name");notes=j.getString("notes");url=j.getString("url");
   bytes=j.getInt("bytes");sha=j.getString("sha256");engineSha=j.getString("engine_sha256");uiSha=j.getString("ui_sha256");
   if(version<1||version>9007199254740991L||name.length()>60||notes.length()>6000||bytes<1||bytes>MAX_PATCH)throw new IOException("패치 정보 범위가 올바르지 않습니다.");
   if(!url.matches("https://github\\.com/jungeunchan0806/irisai-updates/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.zip")||!sha.matches("[a-f0-9]{64}")||!engineSha.matches("[a-f0-9]{64}")||!uiSha.matches("[a-f0-9]{64}"))throw new IOException("패치 주소 또는 검증값이 올바르지 않습니다.");
   this.raw=raw.clone();this.signature=signature.clone();
  }
 }
 public static Manifest verifyManifest(byte[] raw,byte[] sig)throws Exception{
  if(raw.length>MAX_MANIFEST||sig.length>2048)throw new IOException("패치 정보가 너무 큽니다.");
  PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(UpdateConfig.PUBLIC_KEY,Base64.DEFAULT)));
  Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(raw);
  if(!verifier.verify(sig))throw new SecurityException("패치 서명을 확인하지 못했습니다.");
  return new Manifest(raw,sig);
 }
 public static String sha(byte[] bytes)throws Exception{
  StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
 }
 public static byte[] read(InputStream in,int limit)throws IOException{
  try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1){if(n>limit-out.size())throw new IOException("패치 파일 크기 제한을 초과했습니다.");out.write(buffer,0,n);}return out.toByteArray();
  }
 }
 public static byte[] read(File file,int limit)throws IOException{return read(new FileInputStream(file),limit);}
 private static void write(File file,byte[] bytes)throws IOException{
  try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
 }
 private void atomic(String name,String value)throws IOException{
  File temp=new File(root,name+".tmp");write(temp,value.getBytes(StandardCharsets.UTF_8));
  if(!temp.renameTo(new File(root,name)))throw new IOException("패치 상태를 저장하지 못했습니다.");
 }
 private long number(String name){try{return Long.parseLong(new String(read(new File(root,name),32),StandardCharsets.UTF_8).trim());}catch(Exception e){return 0;}}
 private File slot(long version){return new File(root,"v"+version);}
 public long blockedVersion(){return number("failed");}
 public long highestVersion(){return Math.max(UpdateConfig.VERSION,number("highest"));}
 public synchronized void install(Manifest m,byte[] zip,long current)throws Exception{
  if(m.version<=current||m.version<highestVersion())throw new SecurityException("이미 설치했거나 더 오래된 패치입니다.");
  if(zip.length!=m.bytes||!sha(zip).equals(m.sha))throw new SecurityException("다운로드한 패치의 검증값이 다릅니다.");
  Map<String,byte[]> files=new HashMap<>();
  try(ZipInputStream zin=new ZipInputStream(new ByteArrayInputStream(zip))){
   ZipEntry e;while((e=zin.getNextEntry())!=null){
    String name=e.getName();if(e.isDirectory()||!(name.equals("engine.mjs")||name.equals("panel.dex"))||files.containsKey(name))throw new SecurityException("패치 파일 구성이 올바르지 않습니다.");
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;
    while((n=zin.read(b))!=-1){if(n>MAX_PATCH-out.size())throw new IOException("압축 해제 크기가 너무 큽니다.");out.write(b,0,n);}files.put(name,out.toByteArray());zin.closeEntry();
   }
  }
  if(files.size()!=2||!sha(files.get("engine.mjs")).equals(m.engineSha)||!sha(files.get("panel.dex")).equals(m.uiSha))throw new SecurityException("패치 내용 검증에 실패했습니다.");
  byte[] dex=files.get("panel.dex");if(dex.length<8||dex[0]!='d'||dex[1]!='e'||dex[2]!='x'||dex[3]!='\n')throw new SecurityException("UI 패치 형식이 올바르지 않습니다.");
  File target=slot(m.version),temp=new File(root,"staging");remove(temp);temp.mkdirs();
  try{
   for(Map.Entry<String,byte[]> e:files.entrySet())write(new File(temp,e.getKey()),e.getValue());
   write(new File(temp,"manifest.json"),m.raw);write(new File(temp,"manifest.sig"),m.signature);
   remove(target);if(!temp.renameTo(target))throw new IOException("패치를 저장하지 못했습니다.");
   atomic("active",String.valueOf(m.version));
  }finally{remove(temp);}
 }
 public static void remove(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);}file.delete();}
 private Manifest validate(long version)throws Exception{
  File s=slot(version);Manifest m=verifyManifest(read(new File(s,"manifest.json"),MAX_MANIFEST),read(new File(s,"manifest.sig"),2048));
  if(m.version!=version||version<UpdateConfig.VERSION||version<highestVersion())throw new SecurityException("설치된 패치 버전이 올바르지 않습니다.");
  if(!sha(read(new File(s,"engine.mjs"),MAX_PATCH)).equals(m.engineSha)||!sha(read(new File(s,"panel.dex"),MAX_PATCH)).equals(m.uiSha))throw new SecurityException("설치된 패치 파일이 변경되었습니다.");
  return m;
 }
 public synchronized JSONObject select()throws Exception{
  long active=number("active");String recovery="";JSONObject j=new JSONObject();
  if(active>UpdateConfig.VERSION){
   try{
    if(number("pending")==active)throw new IOException("이전 패치 시작이 완료되지 않았습니다.");
    Manifest m=validate(active);atomic("pending",String.valueOf(active));
    return j.put("version",active).put("name",m.name).put("engine",new File(slot(active),"engine.mjs").getAbsolutePath()).put("ui",new File(slot(active),"panel.dex").getAbsolutePath()).put("recovery","");
   }catch(Exception e){atomic("failed",String.valueOf(active));new File(root,"active").delete();new File(root,"pending").delete();recovery="패치 시작에 실패해 기본 버전으로 복구했습니다. "+e.getMessage();}
  }
  return j.put("version",UpdateConfig.VERSION).put("name",UpdateConfig.VERSION_NAME).put("engine","").put("ui","").put("recovery",recovery);
 }
 public synchronized void healthy(long version)throws IOException{
  if(number("pending")==version){
   atomic("highest",String.valueOf(Math.max(version,highestVersion())));
   File pending=new File(root,"pending");
   if(!pending.delete()&&pending.exists())throw new IOException("패치 시작 완료를 저장하지 못했습니다.");
  }
 }
}
