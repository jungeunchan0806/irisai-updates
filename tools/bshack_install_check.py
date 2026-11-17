import json,subprocess,time,re,sys
from pathlib import Path
out=Path('bshack-install-check');out.mkdir(exist_ok=True)
def adb(*args,timeout=60):return subprocess.run(['adb',*args],capture_output=True,text=True,timeout=timeout)
assert adb('get-serialno').stdout.strip().startswith('emulator-')
assert not adb('shell','pm','path','com.supercell.brawlstars').stdout.strip()
r={'friendly_matches':0,'physical_device_tested':False}
try:
 r['storage_before']=adb('shell','df','-h','/data').stdout
 p=adb('install','--streaming','-t',sys.argv[1],timeout=600);r['install_returncode']=p.returncode;r['install_output']=(p.stdout+p.stderr)[-3000:];r['installed']=p.returncode==0 and 'Success' in p.stdout
 if r['installed']:
  adb('logcat','-c');adb('shell','am','start','-W','-n','com.supercell.brawlstars/com.supercell.brawlstars.GameApp',timeout=90)
  time.sleep(30);r['alive_30s']=bool(adb('shell','pidof','com.supercell.brawlstars').stdout.strip())
  logs=adb('logcat','-d','-v','brief').stdout.splitlines();r['diagnostics']=[re.sub(r'https?://\S+','<url>',l) for l in logs if 'AndroidRuntime' in l or 'IRIS BSHACK' in l or re.search(r'Fatal signal|Abort message|#[0-9]+ pc',l)][-80:]
finally:
 (out/'result.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print(json.dumps(r,ensure_ascii=False,indent=2))
if not r.get('installed'):sys.exit(1)
