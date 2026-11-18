"""Pack only the authentic installer verifier and pinned patch inputs into the offline QA app."""
from pathlib import Path
from urllib.request import urlopen
import json, sys, hashlib, zipfile, time

root=Path(__file__).resolve().parents[2]
rows=json.loads(Path(__file__).with_name('inputs.json').read_text())
def download(url):
    for tries in range(3):
        try:
            with urlopen(url,timeout=45) as source: return source.read()
        except OSError:
            if tries==2: raise
            time.sleep(2**tries)
for name,row in rows.items():
    out=Path(sys.argv[1])/name
    out.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(root/row['baseArchive']) as archive: dex=archive.read('panel.dex')
    assert hashlib.sha256(dex).hexdigest()==row['bootstrapSha256'],name
    (out/'baseline.dex').write_bytes(dex)
    for stage in ['previous','current']:
        info=row[stage]
        dest=out/stage
        dest.mkdir(exist_ok=True)
        for file,expected in info['hashes'].items():
            src=info['source']
            data=download(src+'/'+file) if src.startswith('https://') else (root/src/file).read_bytes()
            assert hashlib.sha256(data).hexdigest()==expected, file
            (dest/('patch.zip' if file==info['archive'] else file)).write_bytes(data)
