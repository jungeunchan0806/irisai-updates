import argparse, hashlib, json, sys, zipfile
from pathlib import Path
from repack import repack
from patch_script import patch
BASE_SHA='8d595381fe0247dbfc06bd03879f68828d1dc1967647e830d0c51745d810f082'
SCRIPT='lib/arm64-v8a/libosu.data.so'
p=argparse.ArgumentParser();p.add_argument('baseline',type=Path);p.add_argument('ui',type=Path);p.add_argument('out',type=Path);a=p.parse_args()
with a.baseline.open('rb') as f: assert hashlib.file_digest(f,'sha256').hexdigest()==BASE_SHA,'Wrong baseline'
with zipfile.ZipFile(a.baseline) as z,zipfile.ZipFile(a.ui) as ui:
    assert [n for n in ui.namelist() if n.endswith('.dex')]==['classes.dex']
    script=patch(z.read(SCRIPT));dex=ui.read('classes.dex')
    replacements={SCRIPT:script,'assets/iris-panel.dex':dex}
a.out.parent.mkdir(parents=True,exist_ok=True);repack(a.baseline,a.out,replacements)
(a.out.parent/'agent.mjs').write_bytes(script.split('✄\n'.encode(),1)[1])
print(json.dumps({'unsigned_apk':str(a.out),'baseline_sha256':BASE_SHA,'modified':[SCRIPT],'added':['assets/iris-panel.dex'],'script_bytes':len(script),'ui_dex_bytes':len(dex)}))
