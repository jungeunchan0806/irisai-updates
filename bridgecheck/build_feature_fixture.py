"""Build libc-free x86_64 JNI test boundary. Never included in production APK."""
import os,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
compiler=shutil.which('javac')
if not os.environ.get('JAVA_HOME') and not compiler:raise SystemExit('Set JAVA_HOME to the installed JDK before building the JNI fixture.')
java=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else Path(compiler).resolve().parents[1]
out=root/'bshackcheck/build/feature-native/x86_64/libbrf.so';out.parent.mkdir(parents=True,exist_ok=True)
subprocess.run(['cc','-shared','-fPIC','-nostdlib','-fno-stack-protector','-Wl,-z,max-page-size=16384',
 '-I'+str(java/'include'),'-I'+str(java/'include/linux'),str(root/'bridgecheck/native-feature-fixture.c'),'-o',str(out)],check=True)
print('Built JNI-only feature initialization fixture; no production library replacement.')
