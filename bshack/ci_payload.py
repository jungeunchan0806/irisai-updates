import subprocess,sys
from pathlib import Path
out=Path('bshack-checks');out.mkdir(exist_ok=True)
for p in ['bshackcheck/build/outputs/apk/debug/bshackcheck-debug.apk','bshackcheck/build/outputs/apk/androidTest/debug/bshackcheck-debug-androidTest.apk']:
 subprocess.run(['adb','install','-t',p],check=True)
r=subprocess.run(['adb','shell','am','instrument','-w','com.irisai.bshack.check.test/com.irisai.bshack.check.Checks'],capture_output=True,text=True,timeout=120)
(out/'payload.txt').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr)
(out/'logcat.txt').write_text(subprocess.check_output(['adb','logcat','-d','-t','2000'],text=True))
if r.returncode or 'in-memory payload checks' not in r.stdout or 'FAIL ' in r.stdout:sys.exit(1)
