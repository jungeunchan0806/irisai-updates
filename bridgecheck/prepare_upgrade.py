"""Verify a production update using the immutable verifier from the installed APK."""
from pathlib import Path
import json, zipfile, hashlib, shutil, os

root = Path(__file__).resolve().parents[1]
candidate = (root / os.environ.get('RELEASE_MANIFEST', 'distribution/connection/manifest.json')).resolve().parent
candidate.relative_to(root / 'distribution')
release = json.loads((candidate / 'release.json').read_text())
baseline = root / release['baseline_folder']
out = root / 'bridgecheck/generated/upgrade'
(out / 'baseline').mkdir(parents=True, exist_ok=True)
shutil.copyfile(candidate / 'release.json', out / 'release.json')
with zipfile.ZipFile(baseline / release['baseline_zip']) as archive:
    dex = archive.read('panel.dex')
assert hashlib.sha256(dex).hexdigest() == json.loads((baseline / 'manifest.json').read_text())['ui_sha256']
(out / 'baseline/classes.dex').write_bytes(dex)
(out / 'candidate').mkdir(exist_ok=True)
manifest = json.loads((candidate / 'manifest.json').read_text())
payload = candidate / manifest['url'].rsplit('/', 1)[1]
assert hashlib.sha256(payload.read_bytes()).hexdigest() == manifest['sha256']
for filename in ['manifest.json', 'manifest.sig']:
    shutil.copyfile(candidate / filename, out / 'candidate' / filename)
shutil.copyfile(payload, out / 'candidate/patch.zip')
print('Prepared the original installed verifier and the exact signed production candidate.')
