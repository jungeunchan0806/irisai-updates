"""Reproduce false rollback, then verify the signed fix across repeated app starts.
The installed fixture embeds the exact 2.2.0 verifier/UI. It has no native game library.
"""
from pathlib import Path
import subprocess,time,json,re,xml.etree.ElementTree as E,frida
from connect import connect_gadget

out=Path('recovery-checks');out.mkdir(exist_ok=True)
package='com.irisai.bshack.check';messages=[];checks=[]
candidate=json.loads(Path('bridgecheck/generated/recovery/candidate/manifest.json').read_text())
version=candidate['version'];old_version=22001
bridge=Path('bridgecheck/java-bridge.js').read_text();bootstrap=Path('bshack/bootstrap.js').read_text()
api=Path('bridgecheck/bootstrap-api.js').read_text()+r'''
function legacyStore(){
 const app=_bootJava.use('android.app.ActivityThread').currentApplication();
 const loader=_bootJava.use('dalvik.system.InMemoryDexClassLoader').$new(_bootJava.use('java.nio.ByteBuffer').wrap(_bootRead(app.getAssets().open('iris-bootstrap.dex'))),app.getClassLoader());
 const Store=_bootJava.ClassFactory.get(loader).use('com.irisai.bshack.update.PatchStore');
 return {app,Store,store:Store.$new(app)};
}
rpc.exports={...rpc.exports,
 installpatch(name){return new Promise((resolve,reject)=>_bootJava.perform(()=>{try{
  const {app,Store,store}=legacyStore();const prefix='recovery/'+name+'/';
  const m=Store.verifyManifest(_bootRead(app.getAssets().open(prefix+'manifest.json')),_bootRead(app.getAssets().open(prefix+'manifest.sig')));
  store.install(m,_bootRead(app.getAssets().open(prefix+'patch.zip')),globalThis.__irisBoot.version);resolve(true);
 }catch(e){reject(String(e));}}));},
 selectonly(){return new Promise((resolve,reject)=>_bootJava.perform(()=>{try{resolve(JSON.parse(String(legacyStore().store.select())));}catch(e){reject(String(e));}}));}
};
'''
def adb(*args):return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
def check(value,label):
 assert value,label
 checks.append(label);print('PASS',label,flush=True)
def attach(select_only=False):
 adb('shell','am','start','-W','-n',package+'/com.supercell.brawlstars.GameApp','--ez','bridgecheck','true')
 adb('forward','tcp:27042','tcp:27042')
 session=connect_gadget()
 code=bootstrap.replace('setImmediate(_bootStart);','') if select_only else bootstrap
 script=session.create_script(bridge+code+api,runtime='qjs');script.on('message',lambda m,d:messages.append(m));script.load()
 if select_only:return session,script
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  if script.exports_sync.boot()['selected']:return session,script
  time.sleep(.1)
 raise AssertionError('Bootstrap timeout')
def restart(session):
 session.detach();adb('shell','am','force-stop',package)
 return attach()
def marker(name):
 try:return adb('shell','run-as',package,'cat','files/iris-updates/'+name).strip()
 except subprocess.CalledProcessError:return None
def bounds(node):return [int(v) for v in re.findall(r'\d+',node.attrib['bounds'])]
def find(text,timeout=15):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  adb('shell','uiautomator','dump','/sdcard/iris-recovery-ui.xml')
  tree=E.fromstring(adb('shell','cat','/sdcard/iris-recovery-ui.xml'))
  for node in tree.iter('node'):
   if text in (node.attrib.get('text',''),node.attrib.get('content-desc','')):return tree,node
  time.sleep(.2)
 raise AssertionError('Missing UI: '+text)
def healthy(script):
 deadline=time.monotonic()+5
 while time.monotonic()<deadline:
  boot=script.exports_sync.boot()
  if boot['engineAvailable'] and marker('pending') is None and marker('highest')==str(version):break
  time.sleep(.1)
 check(boot['selected']['version']==version and boot['engineAvailable'] and marker('pending') is None,'candidate completes startup without a native game-ready signal')
 check(marker('highest')==str(version),'startup success is persisted before closing the app')
def screenshot(name):
 with (out/(name+'.png')).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)

adb('install','-r','bshackcheck/build/outputs/apk/debug/bshackcheck-debug.apk');adb('shell','pm','clear',package);adb('logcat','-c')
try:
 session,script=attach();_,node=find('Iris 설정 열기');initial=bounds(node)
 check(script.exports_sync.installpatch('old'),'original verifier accepts the actual 2.2.1 patch')
 session,script=restart(session);find('Iris 설정 열기');time.sleep(9)
 check(script.exports_sync.boot()['selected']['version']==old_version and marker('pending')==str(old_version),'reproduces 2.2.1 keeping a failure marker despite a responsive UI')
 session,script=restart(session)
 boot=script.exports_sync.boot()
 check(boot['selected']['version']==22000 and marker('failed')==str(old_version),'reproduces the user-visible rollback on the next launch')
 check(script.exports_sync.installpatch('candidate'),'higher-version repair installs from the rolled-back state')
 session,script=restart(session);healthy(script)
 tree,node=find('Iris 설정 열기');position=bounds(node);screen=bounds(next(tree.iter('node')))
 check(position[1]==initial[1] and position[2]-position[0]==initial[2]-initial[0] and position[3]-position[1]==initial[3]-initial[1],'transparent opener keeps its original size and vertical position')
 check(abs((position[0]+position[2])-(screen[0]+screen[2]))<=2,'transparent opener is centered horizontally')
 adb('shell','input','tap',str((position[0]+position[2])//2),str((position[1]+position[3])//2))
 find('IRIS  /  컨트롤 센터');screenshot('center-menu-after-repair')
 for _ in range(3):session,script=restart(session);healthy(script)
 check(marker('active')==str(version) and marker('failed')==str(old_version),'three subsequent starts retain the repaired patch')
 # Mark a boot pending without loading its engine/UI, then terminate it. The
 # unchanged installed verifier must still recover from a genuinely incomplete boot.
 session.detach();adb('shell','am','force-stop',package)
 session,script=attach(select_only=True)
 check(script.exports_sync.selectonly()['version']==version,'fixture begins an intentionally incomplete startup')
 session,script=restart(session)
 boot=script.exports_sync.boot()
 check(boot['selected']['version']==22000 and marker('failed')==str(version),'genuinely incomplete patch startup still rolls back')
 print('PASS',len(checks),'patch recovery regression checks',flush=True)
finally:
 (out/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
 (out/'messages.json').write_text(json.dumps(messages,ensure_ascii=False,indent=2)+'\n')
 (out/'logcat.txt').write_text(adb('logcat','-d'))
