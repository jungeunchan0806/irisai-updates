import argparse, hashlib, json, re, struct, zipfile
from pathlib import Path
from patch_script import patch
BASE_SHA='8d595381fe0247dbfc06bd03879f68828d1dc1967647e830d0c51745d810f082'
SCRIPT='lib/arm64-v8a/libosu.data.so'
def digest(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def compressed(f,item):
 f.seek(item.header_offset);header=f.read(30);n,e=struct.unpack_from('<HH',header,26);f.seek(n+e,1);h=hashlib.sha256();remaining=item.compress_size
 while remaining:
  b=f.read(min(8*1024*1024,remaining));assert b;h.update(b);remaining-=len(b)
 return h.digest()
def excluded(n):return bool(re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))',n,re.I))
p=argparse.ArgumentParser();p.add_argument('baseline',type=Path);p.add_argument('apk',type=Path);a=p.parse_args();assert digest(a.baseline)==BASE_SHA
count=0
with zipfile.ZipFile(a.baseline) as old,zipfile.ZipFile(a.apk) as new,a.baseline.open('rb') as of,a.apk.open('rb') as nf:
 o={i.filename:i for i in old.infolist() if not excluded(i.filename)};n={i.filename:i for i in new.infolist() if not excluded(i.filename)}
 assert n.keys()-o.keys()=={'assets/iris-panel.dex'};assert not o.keys()-n.keys()
 for name,x in o.items():
  if name==SCRIPT:continue
  y=n[name];assert (x.CRC,x.file_size,x.compress_size,x.compress_type)==(y.CRC,y.file_size,y.compress_size,y.compress_type),name
  assert compressed(of,x)==compressed(nf,y),name;count+=1
 assert new.read(SCRIPT)==patch(old.read(SCRIPT));assert new.testzip() is None
 b=new.read(SCRIPT);header,body=b.split('✄\n'.encode(),1);assert int(header.decode().splitlines()[1].split()[0])==len(body)
 assert body.endswith(b'export default require_agent();\n')
result={'baseline_sha256':BASE_SHA,'sha256':digest(a.apk),'bytes':a.apk.stat().st_size,'unchanged_entries_byte_verified':count,'modified_entries':[SCRIPT],'added_entries':['assets/iris-panel.dex'],'manifest_and_original_dex_preserved':True,'game_native_libraries_preserved':True,'all_crc':'pass','physical_device_test':'not performed','friendly_match_test':'not performed'}
a.apk.with_suffix('.verification.json').write_text(json.dumps(result,indent=2)+'\n');a.apk.with_suffix('.apk.sha256').write_text(result['sha256']+'  '+a.apk.name+'\n');print(json.dumps(result))
