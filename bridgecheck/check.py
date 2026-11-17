"""Real bootstrap, dynamic module, Android authorization and DEX bridge round trip."""
from pathlib import Path
import json,subprocess,time,frida
out=Path('bridgecheck-results');out.mkdir(exist_ok=True)
subprocess.run(['adb','install','-r','bshackcheck/build/outputs/apk/debug/bshackcheck-debug.apk'],check=True)
subprocess.run(['adb','forward','tcp:27042','tcp:27042'],check=True)
messages=[];checks=[]
source=Path('bridgecheck/java-bridge.js').read_text()+Path('bshack/bootstrap.js').read_text()+Path('bridgecheck/bootstrap-api.js').read_text()
def attach():
 subprocess.run(['adb','shell','am','start','-n','com.irisai.bshack.check/com.supercell.brawlstars.GameApp','--ez','bridgecheck','true'],check=True)
 time.sleep(2)
 device=frida.get_device_manager().add_remote_device('127.0.0.1:27042')
 session=device.attach('Gadget');script=session.create_script(source,runtime='qjs')
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
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  try:
   s=state(script)
   if s['foreground'] and s['auth']['ready']:break
  except frida.core.RPCException:pass
  time.sleep(.25)
 check(s['foreground'] and s['auth']['ready'],'bootstrap loads production DEX and Android authorization')
 check(not s['enabled'] and not s['auth']['active'],'unregistered device starts disabled')
 check(script.exports_sync.grant(),'fixture supplies a bounded authorized lease')
 time.sleep(.5)
 for i in range(6):
  check(script.exports_sync.click(),'real Android toggle dispatches')
  time.sleep(.45);s=state(script)
  check(s['enabled']==(i%2==0) and not s['error'],'typed authorization commits engine ON/OFF')
 script.exports_sync.failonce();time.sleep(1)
 check(not state(script)['error'],'UI bridge recovers after transient exception')
 check(script.exports_sync.installfixture(),'verified small patch installs without replacing APK')
 restart(session);session,script=attach();time.sleep(1)
 b=script.exports_sync.boot()
 check(b['selected']['version']==22001 and b['canary'],'signed downloaded engine executes on next start')
 restart(session);session,script=attach();time.sleep(1)
 b=script.exports_sync.boot()
 check(b['selected']['version']==22000 and bool(b['selected']['recovery']),'unfinished patched startup rolls back to embedded engine')
 print('PASS',len(checks),'real bootstrap/bridge checks',flush=True)
finally:
 (out/'messages.json').write_text(json.dumps(messages,ensure_ascii=False,indent=2))
 (out/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
 (out/'logcat.txt').write_text(subprocess.check_output(['adb','logcat','-d','-t','2000'],text=True))
 print(json.dumps(messages[-12:],ensure_ascii=False))
