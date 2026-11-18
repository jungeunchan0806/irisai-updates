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

 # Exercise the production guard through actual Frida, Java UI and mapped native memory.
 def connection_wait(predicate,label):
  end=time.monotonic()+7;current={}
  while time.monotonic()<end:
   current=script.exports_sync.connection()
   if predicate(current):return current
   time.sleep(.1)
  raise AssertionError(label+': '+str(current))
 script.exports_sync.link('online');script.exports_sync.grant();script.exports_sync.click()
 connection_wait(lambda s:s['ready'] and s['enabled'] and s['inputs']>0,'initial connection')
 for fault,reason in [('offline','offline'),('stalled','stalled'),('screen','screen')]:
  script.exports_sync.link(fault)
  held=connection_wait(lambda s:s['reason']==reason,'connection hold '+fault)
  time.sleep(.4);after=script.exports_sync.connection()
  check(after['inputs']==held['inputs'] and after['enabled'],'connection hold stops input without losing intent: '+fault)
  script.exports_sync.link('online')
  connection_wait(lambda s:s['ready'] and s['inputs']>held['inputs'],'recovery '+fault)
  check(state(script)['enabled'],'stable fresh state resumes enabled Auto Goal: '+fault)
 script.exports_sync.link('offline');connection_wait(lambda s:s['reason']=='offline','offline before stop')
 script.exports_sync.click();time.sleep(.35);script.exports_sync.link('online')
 connection_wait(lambda s:s['ready'],'recovery after user stop')
 check(not state(script)['enabled'],'recovery never re-enables a user-stopped Auto Goal')

 script.exports_sync.failonce();time.sleep(1)
 check(not state(script)['error'],'UI bridge recovers after transient exception')
 check(script.exports_sync.installfixture(),'verified small patch installs without replacing APK')
 restart(session);session,script=attach()
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  b=script.exports_sync.boot()
  if b['engineAvailable']:break
  time.sleep(.25)
 check(b['selected']['version']==22001 and b['engineAvailable'],'signed full production engine loads on next start')
 restart(session);session,script=attach();time.sleep(1)
 b=script.exports_sync.boot()
 check(b['selected']['version']==22000 and bool(b['selected']['recovery']),'unfinished patched startup rolls back to embedded engine')
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  try:
   s=state(script)
   if s.get('foreground') and s.get('auth',{}).get('ready'):break
  except frida.core.RPCException:pass
  time.sleep(.25)
 check(s.get('foreground') and s.get('auth',{}).get('ready'),'embedded UI and authorization recover after rollback')
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
