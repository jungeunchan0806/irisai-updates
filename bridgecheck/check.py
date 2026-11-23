"""Real bootstrap, dynamic module, Android authorization and DEX bridge round trip."""
from pathlib import Path
import json,subprocess,time,frida,os,re
from connect import connect_gadget
out=Path('bridgecheck-results');out.mkdir(exist_ok=True)
subprocess.run(['adb','install','-r','bshackcheck/build/outputs/apk/debug/bshackcheck-debug.apk'],check=True)
subprocess.run(['adb','shell','pm','clear','com.irisai.bshack.check'],check=True)
subprocess.run(['adb','forward','tcp:27042','tcp:27042'],check=True)
messages=[];checks=[]
source=Path('bridgecheck/java-bridge.js').read_text()+Path('bshack/bootstrap.js').read_text()+Path('bridgecheck/bootstrap-api.js').read_text()
def attach():
 subprocess.run(['adb','shell','am','start','-W','-n','com.irisai.bshack.check/com.supercell.brawlstars.GameApp','--ez','bridgecheck','true'],check=True)
 session=connect_gadget();script=session.create_script(source,runtime='qjs')
 script.on('message',lambda message,data:messages.append(message));script.load()
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  boot=script.exports_sync.boot()
  if boot['selected']:return session,script
  time.sleep(.25)
 raise AssertionError('Bootstrap did not select a version')
def restart(session):
 session.detach();subprocess.run(['adb','shell','am','force-stop','com.irisai.bshack.check'],check=True);time.sleep(.5)
def state(script):return script.exports_sync.state()
def check(value,label):
 assert value,label
 checks.append(label);print('PASS',label,flush=True)
try:
 session,script=attach()
 s={}
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  try:
   s=state(script)
   if s['foreground'] and s['auth']['ready']:break
  except frida.core.RPCException:pass
  time.sleep(.25)
 check(s.get('foreground') and s.get('auth',{}).get('ready'),'bootstrap loads production DEX and Android authorization')
 check(not s['enabled'] and not s['auth']['active'],'unregistered device starts disabled')
 check(script.exports_sync.grant(),'fixture supplies a bounded authorized lease')
 time.sleep(.5)
 for i in range(6):
  check(script.exports_sync.click(),'real Android toggle dispatches')
  time.sleep(.45);s=state(script)
  check(s['enabled']==(i%2==0) and not s['error'],'typed authorization commits engine ON/OFF')
 script.exports_sync.failonce();time.sleep(1)
 check(not state(script)['error'],'UI bridge recovers after transient exception')
 ui=script.exports_sync.inspectui('Iris 설정 열기')
 check(ui['clicked'] and abs(ui['centerX']-ui['rootWidth']/2)<=2,'invisible opener is at top center and opens menu')
 time.sleep(.3)
 ui=script.exports_sync.inspectui('')
 check(any('컨트롤 센터' in t for t in ui['texts']),'large settings menu is visible')
 check(not any(re.search(r'업데이트|패치 복구|Anti AFK|연결 복구 보호',t,re.I) for t in ui['texts']),'update and Anti AFK menu entries are absent')
 check(any('discord.gg/bshack' in t for t in ui['texts']),'existing Discord link is retained')
 check(script.exports_sync.inspectui('설정 닫기')['clicked'],'large menu closes')
 # Populate old persisted update state, without modifying user settings.
 subprocess.run(['adb','shell','run-as','com.irisai.bshack.check','mkdir','-p','files/iris-updates/v99999'],check=True)
 for name in ['active','pending','failed','highest']:
  subprocess.run(['adb','shell',f'run-as com.irisai.bshack.check sh -c "echo 99999 > files/iris-updates/{name}"'],check=True)
 for iteration in range(3):
  restart(session);session,script=attach()
  deadline=time.monotonic()+15
  while time.monotonic()<deadline:
   try:
    s=state(script)
    if s.get('foreground') and s.get('auth',{}).get('ready'):break
   except frida.core.RPCException:pass
   time.sleep(.25)
  b=script.exports_sync.boot()['selected']
  check(b['version']==22500 and not b['engine'] and not b['ui'] and not b['recovery'],'restart '+str(iteration+1)+' ignores legacy patch state')
  check(s.get('foreground') and s.get('auth',{}).get('ready'),'bundled UI and authorization load again')
  check(script.exports_sync.grant(),'fixture lease renews after restart')
  time.sleep(.5)
  check(script.exports_sync.click(),'bundled Auto Goal toggle still dispatches after restart')
  time.sleep(.5)
  check(state(script)['enabled'],'bundled engine receives ON after restart')
 minutes=int(os.environ.get('ENDURANCE_MINUTES','0'))
 if minutes:
  checkpoints=[];stop=time.monotonic()+minutes*60;last_grant=0;last_memory=0;cycles=0;max_rpc=0
  while time.monotonic()<stop:
   start=time.monotonic()
   if start-last_grant>40:script.exports_sync.grant();last_grant=start
   script.exports_sync.click();time.sleep(.4);s=state(script)
   assert not s['error'] and s['auth']['ready'] and s['foreground'],s
   max_rpc=max(max_rpc,time.monotonic()-start);cycles+=1
   if start-last_memory>30:
    memory=subprocess.check_output(['adb','shell','dumpsys','meminfo','com.irisai.bshack.check'],text=True)
    match=re.search(r'TOTAL PSS:\s*(\d+)',memory)
    checkpoints.append({'cycle':cycles,'remaining_seconds':round(stop-start),'pss_kb':int(match.group(1)) if match else None})
    print('Endurance',checkpoints[-1],flush=True);last_memory=start
   time.sleep(1.6)
  (out/'endurance.json').write_text(json.dumps({'minutes':minutes,'cycles':cycles,'max_rpc_seconds':max_rpc,'checkpoints':checkpoints},indent=2))
  check(cycles>=minutes*15,'sustained authorization and UI bridge remains responsive')
  measured=[x['pss_kb'] for x in checkpoints if x['pss_kb'] is not None]
  if len(measured)>2:check(measured[-1]-measured[1]<80*1024,'bridge memory growth remains below 80 MiB across endurance run')
 print('PASS',len(checks),'real bootstrap/bridge checks',flush=True)
finally:
 (out/'messages.json').write_text(json.dumps(messages,ensure_ascii=False,indent=2))
 (out/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
 (out/'logcat.txt').write_text(subprocess.check_output(['adb','logcat','-d','-t','2000'],text=True))
 print(json.dumps(messages[-12:],ensure_ascii=False))
