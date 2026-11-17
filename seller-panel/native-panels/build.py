"""Prepare compatible panel updates; final signing requires the ORIGINAL panel key."""
import argparse,hashlib,json,os,re,struct,subprocess,zipfile,zlib
from pathlib import Path

SIGNER='11c5f03c3f98613d9e230a35382c3af592b83826f3092d2c2c306bc873dcc71f'

def length(b,p,utf8):
 if utf8:
  n=b[p];return (((n&127)<<8)|b[p+1],p+2) if n&128 else (n,p+1)
 n=struct.unpack_from('<H',b,p)[0]
 return (((n&32767)<<16)|struct.unpack_from('<H',b,p+2)[0],p+4) if n&32768 else (n,p+2)

def manifest_version(data,old_name):
 b=bytearray(data);strings=[];pos=8;changed=0;version_codes=0
 while pos<len(b):
  kind,head,size=struct.unpack_from('<HHI',b,pos)
  if size<8 or pos+size>len(b):raise ValueError('Invalid Android binary XML')
  if kind==1:
   count=struct.unpack_from('<I',b,pos+8)[0];utf8=bool(struct.unpack_from('<I',b,pos+16)[0]&256);start=pos+struct.unpack_from('<I',b,pos+20)[0]
   for i in range(count):
    p=start+struct.unpack_from('<I',b,pos+head+i*4)[0];n,p=length(b,p,utf8)
    if utf8:n,p=length(b,p,True)
    size_bytes=n if utf8 else n*2;encoding='utf-8' if utf8 else 'utf-16le';value=bytes(b[p:p+size_bytes]).decode(encoding);strings.append(value)
    if value==old_name:
     replacement='2.2.0'.encode(encoding);assert len(replacement)==size_bytes;b[p:p+size_bytes]=replacement;changed+=1
  elif kind==0x102:
   name=struct.unpack_from('<I',b,pos+20)[0]
   if strings[name]=='manifest':
    start,attr_size,count=struct.unpack_from('<HHH',b,pos+24)
    for i in range(count):
     p=pos+16+start+i*attr_size;idx=struct.unpack_from('<I',b,p+4)[0]
     if strings[idx]=='versionCode':assert b[p+15] in (0x10,0x11);struct.pack_into('<I',b,p+16,22000);version_codes+=1
  pos+=size
 assert changed==1 and version_codes==1,(changed,version_codes)
 return bytes(b)

def admin_version(data):
 b=bytearray(data);count,table=struct.unpack_from('<II',b,56);changed=0
 for i in range(count):
  p=struct.unpack_from('<I',b,table+i*4)[0]
  while b[p]&128:p+=1
  p+=1;end=b.index(0,p);raw=bytes(b[p:end])
  if b'BSHACK-Admin/1.2.0' in raw or b'?app=admin-android&version=1.2.0' in raw:
   b[p:end]=raw.replace(b'1.2.0',b'2.2.0');changed+=1
 assert changed>=1
 b[12:32]=hashlib.sha1(b[32:]).digest();struct.pack_into('<I',b,8,zlib.adler32(b[12:])&0xffffffff)
 return bytes(b)

def run(args):return subprocess.check_output([str(x) for x in args],text=True,stderr=subprocess.STDOUT)
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--sdk-tools',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
 p.add_argument('--prepare-only',action='store_true');p.add_argument('--key',type=Path);p.add_argument('--cert',type=Path)
 p.add_argument('--keystore',type=Path);p.add_argument('--alias');p.add_argument('--password-file',type=Path)
 a=p.parse_args();root=Path(__file__).resolve().parents[1];a.out.mkdir(parents=True,exist_ok=True)
 if not a.prepare_only and not ((a.key and a.cert) or (a.keystore and a.alias and a.password_file)):p.error('Supply the original signing configuration, or --prepare-only.')
 report={'version':'2.2.0','expected_signer':SIGNER,'signed':not a.prepare_only,'files':[]}
 for role,source,old in [('Seller',root/'public/downloads/seller-2.0.0/BSHACK-Seller-Panel-Android.apk','2.0.0'),('Admin',root/'public/downloads/admin-1.2.0/BSHACK-Admin-Android-1.2.0.apk','1.2.0')]:
  assert SIGNER in run([a.sdk_tools/'apksigner','verify','--print-certs',source]),'Wrong panel baseline signer'
  temp=a.out/(role+'.raw.apk');aligned=a.out/('BSHACK-'+role+'-Android-2.2.0.unsigned.apk')
  with zipfile.ZipFile(source) as src,zipfile.ZipFile(temp,'w') as dest:
   for info in src.infolist():
    if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))',info.filename,re.I):continue
    data=src.read(info.filename)
    if info.filename=='AndroidManifest.xml':data=manifest_version(data,old)
    elif role=='Seller' and info.filename=='assets/panel.html':data=(root/'seller-client/panel.html').read_bytes()
    elif role=='Admin' and info.filename=='classes.dex':data=admin_version(data)
    dest.writestr(info,data)
  run([a.sdk_tools/'zipalign','-f','4',temp,aligned]);temp.unlink()
  with zipfile.ZipFile(aligned) as z:assert z.testzip() is None
  assert "versionCode='22000'" in run([a.sdk_tools/'aapt2','dump','badging',aligned])
  result=aligned
  if not a.prepare_only:
   result=a.out/('BSHACK-'+role+'-Android-2.2.0.apk');args=[a.sdk_tools/'apksigner','sign','--v4-signing-enabled','false','--out',result]
   if a.key:args+=['--key',a.key,'--cert',a.cert]
   else:args+=['--ks',a.keystore,'--ks-key-alias',a.alias,'--ks-pass','file:'+str(a.password_file)]
   run(args+[aligned]);verification=run([a.sdk_tools/'apksigner','verify','--verbose','--print-certs',result])
   if SIGNER not in verification:result.unlink();raise SystemExit('STOP: signing key does not match the installed panel. No incompatible APK was produced.')
   aligned.unlink()
  report['files'].append({'name':result.name,'bytes':result.stat().st_size,'sha256':hashlib.sha256(result.read_bytes()).hexdigest()})
 (a.out/'panel-verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
