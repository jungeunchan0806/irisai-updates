"""Create a signed two-file update; signing key is kept outside the repository."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path

def package(engine:bytes,dex:bytes,dest:Path,version:int,name:str,notes:str,url:str,key:Path):
 dest.mkdir(parents=True,exist_ok=True)
 archive=dest/'patch.zip'
 with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
  for filename,content in [('engine.mjs',engine),('panel.dex',dex)]:
   info=zipfile.ZipInfo(filename,date_time=(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,content)
 digest=lambda b:hashlib.sha256(b).hexdigest()
 m={'schema':1,'base':'bshack-70648-panel6-v1','version':version,'name':name,'notes':notes,'url':url,
  'bytes':archive.stat().st_size,'sha256':digest(archive.read_bytes()),'engine_sha256':digest(engine),'ui_sha256':digest(dex)}
 manifest=dest/'manifest.json';manifest.write_text(json.dumps(m,ensure_ascii=False,separators=(',',':'))+'\n')
 subprocess.run(['openssl','dgst','-sha256','-sign',str(key),'-out',str(dest/'manifest.sig'),str(manifest)],check=True)
 return m

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--engine',type=Path,required=True);p.add_argument('--ui',type=Path,required=True)
 p.add_argument('--out',type=Path,required=True);p.add_argument('--key',type=Path,required=True);p.add_argument('--version',type=int,required=True)
 p.add_argument('--name',required=True);p.add_argument('--notes',type=Path,required=True);p.add_argument('--url',required=True);a=p.parse_args()
 with zipfile.ZipFile(a.ui) as z:dex=z.read('classes.dex')
 m=package(a.engine.read_bytes(),dex,a.out,a.version,a.name,a.notes.read_text(),a.url,a.key)
 print(json.dumps({'version':m['version'],'bytes':m['bytes'],'sha256':m['sha256']},indent=2))
