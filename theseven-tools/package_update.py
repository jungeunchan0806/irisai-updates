"""Create a data-only patch and signed update index alongside the two previously signed APKs."""
import argparse, base64, hashlib, json, subprocess
from pathlib import Path

def encode(obj):
    return (json.dumps(obj, ensure_ascii=False, separators=(",", ":"))+"\n").encode()

def entry(path):
    return {"file":path.name,"sha256":hashlib.sha256(path.read_bytes()).hexdigest()}

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--key",type=Path,required=True)
    parser.add_argument("--catalog",type=Path,required=True)
    parser.add_argument("--buyer",type=Path,required=True)
    parser.add_argument("--seller",type=Path,required=True)
    parser.add_argument("--app-code",type=int,required=True)
    parser.add_argument("--min-app",type=int,required=True)
    parser.add_argument("--notes",required=True)
    args=parser.parse_args()
    catalog=json.loads(args.catalog.read_text())
    if catalog["schema"]!=1:raise ValueError("Unknown schema")
    out=args.buyer.parent
    if args.seller.parent!=out:raise ValueError("Place both APKs in one release directory")
    apps={"com.irisai.externalplayer":entry(args.buyer),"com.theseven.seller":entry(args.seller)}
    for apk in apps.values():apk["versionCode"]=args.app_code
    patch=out/f'theseven-catalog-v{catalog["version"]}.json'
    patch.write_bytes(encode(catalog))
    manifest=encode(dict(schema=1,channel="theseven-practice",catalog=dict(**entry(patch),version=catalog["version"],minApp=args.min_app),apps=apps,notes=args.notes))
    sig=subprocess.run(["openssl","dgst","-sha256","-sign",str(args.key)],input=manifest,capture_output=True,check=True).stdout
    (out/"theseven-update.json").write_bytes(encode({k:base64.b64encode(v).decode() for k,v in {"payload":manifest,"signature":sig}.items()}))
