"""Reproduce the static APK from a published, hash-pinned baseline and payload."""
import argparse, hashlib, json, re, struct, zipfile
from pathlib import Path
from repack import repack
BASE_SHA = '99b33527f79ab2b277c90ae428df06d7450269aadeee7b77918e960106e1eb54'
ENTRIES = {'lib/arm64-v8a/libosu.data.so', 'assets/iris-panel.dex', 'assets/iris-bootstrap.dex', 'assets/iris-engine.mjs'}
def sha(path):
    with Path(path).open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()
def excluded(name): return bool(re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I))
def raw_digest(f, i):
    f.seek(i.header_offset); h=f.read(30); a,b=struct.unpack_from('<HH',h,26); f.seek(a+b,1)
    digest=hashlib.sha256(); left=i.compress_size
    while left:
        block=f.read(min(left,8*1024*1024)); assert block; digest.update(block); left-=len(block)
    return digest.digest()
p=argparse.ArgumentParser();p.add_argument('mode',choices=['repack','verify']);p.add_argument('baseline',type=Path);p.add_argument('payload',type=Path);p.add_argument('apk',type=Path);a=p.parse_args()
assert sha(a.baseline)==BASE_SHA, 'Published baseline hash mismatch'
with zipfile.ZipFile(a.payload) as z:
    assert set(z.namelist())==ENTRIES
    replacements={n:z.read(n) for n in ENTRIES}
assert b'iris-updates' not in replacements['lib/arm64-v8a/libosu.data.so']
for value in [b'PatchStore', b'UpdateManager', b'ConnectionGate', b'Anti AFK']:
    assert value not in replacements['assets/iris-panel.dex'], value
assert b'_cgTick' not in replacements['assets/iris-engine.mjs']
assert b'fixture-only' not in replacements['assets/iris-engine.mjs'].lower()
if a.mode=='repack':
    repack(a.baseline,a.apk,replacements)
else:
    count=0
    with zipfile.ZipFile(a.baseline) as old,zipfile.ZipFile(a.apk) as new,a.baseline.open('rb') as of,a.apk.open('rb') as nf:
        o={i.filename:i for i in old.infolist() if not excluded(i.filename)}
        n={i.filename:i for i in new.infolist() if not excluded(i.filename)}
        assert o.keys()==n.keys()
        for name,item in o.items():
            if name in replacements:
                assert new.read(name)==replacements[name],name
            else:
                assert raw_digest(of,item)==raw_digest(nf,n[name]),name
                count+=1
        assert new.testzip() is None
    report={'version':'2.2.5-static','baseline_sha256':BASE_SHA,'apk_sha256':sha(a.apk),'bytes':a.apk.stat().st_size,'unchanged_entries_byte_verified':count,'changed_entries':sorted(ENTRIES),'patch_loader_removed':True,'anti_afk_absent':True,'real_match_tested':False,'physical_device_tested':False}
    a.apk.with_suffix('.verification.json').write_text(json.dumps(report,indent=2)+'\n')
    a.apk.with_suffix('.apk.sha256').write_text(report['apk_sha256']+'  '+a.apk.name+'\n')
    print(json.dumps(report))
