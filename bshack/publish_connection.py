"""Publish only after the calling workflow's Android checks pass."""
from pathlib import Path
import base64, hashlib, json, os, re, subprocess, tempfile, urllib.request, zipfile, sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'distribution/connection')
release = json.loads((root / 'release.json').read_text())
manifest = json.loads((root / 'manifest.json').read_text())
repository = os.environ['GITHUB_REPOSITORY']
assert repository == 'jungeunchan0806/irisai-updates'
assert manifest['version'] == release['version'] and manifest['base'] == release['base']
assert manifest['url'] == f"https://github.com/{repository}/releases/download/{release['tag']}/{release['filename']}"
config = Path('bshackui/src/main/java/com/irisai/bshack/update/UpdateConfig.java').read_text()
key = re.search(r'PUBLIC_KEY="([A-Za-z0-9+/=]+)"', config).group(1)
channel_url = f"https://github.com/{repository}/releases/download/{release['channel']}/stable.json"
assert channel_url in config

def gh(*args):
    return subprocess.run(['gh', *args], check=True)

with tempfile.TemporaryDirectory() as temp:
    temp = Path(temp)
    (temp / 'public.der').write_bytes(base64.b64decode(key))
    subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(temp / 'public.der'), '-keyform', 'DER',
                    '-signature', str(root / 'manifest.sig'), str(root / 'manifest.json')], check=True)
    payload = root / release['filename']
    assert payload.stat().st_size == manifest['bytes']
    assert hashlib.sha256(payload.read_bytes()).hexdigest() == manifest['sha256']
    with zipfile.ZipFile(payload) as archive:
        assert sorted(archive.namelist()) == ['engine.mjs', 'panel.dex']
        for name, field in [('engine.mjs', 'engine_sha256'), ('panel.dex', 'ui_sha256')]:
            assert hashlib.sha256(archive.read(name)).hexdigest() == manifest[field]
    # Do not replace a newer channel or mutate an already published version.
    with urllib.request.urlopen(channel_url, timeout=30) as response:
        current = json.load(response)
    assert current['base'] == manifest['base'] and current['version'] <= manifest['version']
    if current['version'] == manifest['version']:
        assert current == manifest, 'Existing version has different contents'
    notes = (root / 'RELEASE-KO.md').read_text()
    notes += f"\n[이 소스의 Android 자동 검사 결과](https://github.com/{repository}/actions/runs/{os.environ['GITHUB_RUN_ID']})\n"
    (temp / 'release.md').write_text(notes)
    tag = release['tag']
    exists = subprocess.run(['gh', 'release', 'view', tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not exists:
        gh('release', 'create', tag, '--target', os.environ['GITHUB_SHA'], '--title', release['title'],
           '--notes-file', str(temp / 'release.md'), '--draft', '--prerelease', '--latest=false')
    gh('release', 'upload', tag, str(payload), str(root / 'manifest.json'), str(root / 'manifest.sig'), '--clobber')
    gh('release', 'edit', tag, '--notes-file', str(temp / 'release.md'), '--draft=false', '--prerelease', '--latest=false')
    # Publish assets before their signed channel pointer. Mixed generations fail
    # signature verification and are retried by the existing updater.
    (temp / 'stable.json').write_bytes((root / 'manifest.json').read_bytes())
    (temp / 'stable.json.sig').write_bytes((root / 'manifest.sig').read_bytes())
    gh('release', 'upload', release['channel'], str(temp / 'stable.json.sig'), '--clobber')
    gh('release', 'upload', release['channel'], str(temp / 'stable.json'), '--clobber')
print('Published signed patch and its compatible update channel:', release['tag'])
