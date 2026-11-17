"""Developer-only signing of isolated update fixtures. No key is shipped to CI."""
import sys,json,zipfile,hashlib,subprocess
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'bshack'))
from package_patch import package
from patch_script import patch
root=Path(__file__).resolve().parent
key=Path(sys.argv[1])
with zipfile.ZipFile(root.parent/'bshackui/build/outputs/apk/release/bshackui-release-unsigned.apk') as z:dex=z.read('classes.dex')
with zipfile.ZipFile(sys.argv[2]) as z:engine=patch(z.read('lib/arm64-v8a/libosu.data.so')).split('✄\n'.encode(),1)[1]
package(engine,dex,root/'fixtures',22001,'2.2.1-test','Isolated fixture only. Never publish to the update channel.',
 'https://github.com/jungeunchan0806/irisai-updates/releases/download/bridge-fixture-test/fixture.zip',key)
print('Signed isolated patch fixtures; private key stays outside the repository.')
