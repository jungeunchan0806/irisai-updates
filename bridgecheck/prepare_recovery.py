"""Prepare signed production patches and the immutable 2.2.0 DEX for restart tests."""
from pathlib import Path
import sys,json,zipfile,hashlib,shutil
root=Path(__file__).resolve().parents[1]
candidate=(root/sys.argv[1]).resolve();candidate.relative_to(root/'distribution')
out=root/'bridgecheck/generated/recovery';out.mkdir(parents=True,exist_ok=True)
baseline=root/'distribution/bshack-panel6'
with zipfile.ZipFile(baseline/'BSHACK-patch-2.2.0.zip') as archive:dex=archive.read('panel.dex')
assert hashlib.sha256(dex).hexdigest()==json.loads((baseline/'manifest.json').read_text())['ui_sha256']
(out/'baseline').mkdir(exist_ok=True);(out/'baseline/classes.dex').write_bytes(dex)
for name,folder in [('old',root/'distribution/bshack-patch-2.2.1'),('candidate',candidate)]:
    target=out/name;target.mkdir(exist_ok=True)
    manifest=json.loads((folder/'manifest.json').read_text())
    for filename in ['manifest.json','manifest.sig']:shutil.copyfile(folder/filename,target/filename)
    payload=folder/manifest['url'].rsplit('/',1)[1]
    assert hashlib.sha256(payload.read_bytes()).hexdigest()==manifest['sha256']
    shutil.copyfile(payload,target/'patch.zip')
print('Prepared the exact 2.2.0 verifier, failing 2.2.1 and signed candidate patch.')
