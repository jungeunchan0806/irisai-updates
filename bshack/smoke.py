"""Compare launch of unchanged baseline and update in a disposable emulator only."""
import json,subprocess,time,sys,re
from pathlib import Path
out=Path('bshack-game-launch');out.mkdir(exist_ok=True)
def adb(*args,timeout=60,check=True):return subprocess.run(['adb',*args],capture_output=True,text=True,timeout=timeout,check=check)
assert adb('get-serialno').stdout.strip().startswith('emulator-'),'Disposable emulator required'
assert not adb('shell','pm','path','com.supercell.brawlstars',check=False).stdout.strip(),'Refusing existing installation'
report={'friendly_matches':0,'physical_device_tested':False,'android_api':adb('shell','getprop','ro.build.version.sdk').stdout.strip(),'abi':adb('shell','getprop','ro.product.cpu.abilist').stdout.strip()}
for name,path in [('baseline',sys.argv[1]),('candidate',sys.argv[2])]:
 data={};report[name]=data
 try:
  r=adb('install','-r','-t',path,timeout=600);data['installed']='Success' in r.stdout
  adb('shell','am','force-stop','com.supercell.brawlstars');adb('logcat','-c')
  adb('shell','am','start','-W','-n','com.supercell.brawlstars/com.supercell.brawlstars.GameApp',timeout=90,check=False)
  data['samples']=[]
  for seconds in [15,30]:
   time.sleep(15);data['samples'].append({'seconds':seconds,'alive':bool(adb('shell','pidof','com.supercell.brawlstars',check=False).stdout.strip())})
  data['alive_30s']=data['samples'][-1]['alive']
  image=subprocess.run(['adb','exec-out','screencap','-p'],capture_output=True,timeout=30).stdout
  if image.startswith(b'\x89PNG'):(out/(name+'.png')).write_bytes(image)
  if name=='candidate' and data['alive_30s']:
   adb('shell','uiautomator','dump','/sdcard/window.xml',timeout=45,check=False)
   text=adb('shell','cat','/sdcard/window.xml',check=False).stdout
   data['ui_visible']='오토골 OFF' in text or 'Auto Goal OFF' in text
  logs=adb('logcat','-d','-v','brief',check=False).stdout.splitlines()
  relevant=[line for line in logs if 'AndroidRuntime' in line or 'IRIS BSHACK' in line or 'BSHACK 2.0.1' in line or re.search(r'Fatal signal|Abort message|#[0-9]+ pc',line)]
  data['diagnostics']=[re.sub(r'https?://\S+','<url>',line) for line in relevant][-100:]
 except Exception as e:data['error']=str(e)
 finally:(out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False,indent=2))
