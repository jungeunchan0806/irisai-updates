"""Use the published signed patch through the real confirmation/download/restart UI.
The isolated fixture is compiled as version 21999; no modified fixture APK is distributed.
"""
from pathlib import Path
import subprocess,time,json,re,xml.etree.ElementTree as E,frida

out=Path('network-update-checks');out.mkdir(exist_ok=True)
package='com.irisai.bshack.check';messages=[];checks=[]
source=Path('bridgecheck/java-bridge.js').read_text()+Path('bshack/bootstrap.js').read_text()+Path('bridgecheck/bootstrap-api.js').read_text()
def adb(*args):return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
def check(value,label):
 assert value,label
 checks.append(label);print('PASS',label,flush=True)
def attach():
 adb('shell','am','start','-W','-n',package+'/com.supercell.brawlstars.GameApp','--ez','bridgecheck','true');time.sleep(1)
 adb('forward','tcp:27042','tcp:27042')
 device=frida.get_device_manager().add_remote_device('127.0.0.1:27042');session=device.attach('Gadget')
 script=session.create_script(source,runtime='qjs');script.on('message',lambda m,d:messages.append(m));script.load()
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  boot=script.exports_sync.boot()
  if boot['selected']:return session,script
  time.sleep(.2)
 raise AssertionError('Bootstrap timeout')
def tree():
 adb('shell','uiautomator','dump','/sdcard/iris-network-ui.xml')
 return E.fromstring(adb('shell','cat','/sdcard/iris-network-ui.xml'))
def find(text,contains=False,timeout=50):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  root=tree()
  for n in root.iter('node'):
   values=[n.attrib.get('text',''),n.attrib.get('content-desc','')]
   if any(text in v if contains else text==v for v in values):return root,n
  time.sleep(.5)
 raise AssertionError('UI missing: '+text)
def click(node):
 nums=[int(v) for v in re.findall(r'\d+',node.attrib['bounds'])];adb('shell','input','tap',str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2))
def confirm():
 root=tree();buttons=[n for n in root.iter('node') if n.attrib.get('resource-id')=='android:id/button1']
 assert len(buttons)==1;click(buttons[0])
def screen(name):
 with (out/(name+'.png')).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
def package_stamp():return '\n'.join(v.strip() for v in adb('shell','dumpsys','package',package).splitlines() if 'lastUpdateTime=' in v or 'versionCode=' in v)
adb('install','-r','bshackcheck/build/outputs/apk/debug/bshackcheck-debug.apk');adb('shell','pm','clear',package);adb('logcat','-c')
try:
 before=package_stamp();session,script=attach()
 check(script.exports_sync.boot()['selected']['version']==21999,'fixture begins at an older embedded patch version')
 root,node=find('업데이트 하시겠습니까?')
 check(node is not None,'startup automatically detects the published GitHub update')
 texts=[n.attrib.get('text','') for n in root.iter('node')]
 check('확인' in texts and not any(v in texts for v in ['취소','거절','나중에']),'available update has only confirmation')
 screen('automatic-offer');confirm()
 find('업데이트 준비 완료',timeout=70)
 check(adb('shell','run-as',package,'cat','files/iris-updates/active').strip()=='22000','real HTTPS payload is verified and staged')
 screen('ready-to-apply');confirm();time.sleep(1)
 try:session.detach()
 except frida.InvalidOperationError:pass
 session,script=attach();deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  boot=script.exports_sync.boot()
  if boot['engineAvailable']:break
  time.sleep(.25)
 check(boot['selected']['version']==22000 and boot['engineAvailable'],'published production engine runs after restart')
 check(package_stamp()==before,'update did not reinstall or replace the APK')
 _,opener=find('Iris 설정 열기');click(opener)
 _,card=find('업데이트 확인');click(card)
 find('최신 버전입니다.',contains=True,timeout=50)
 check(True,'large UI manual update check reports the installed version as current')
 screen('manual-up-to-date');confirm()
 print('PASS',len(checks),'published-network update checks',flush=True)
finally:
 (out/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
 (out/'messages.json').write_text(json.dumps(messages,ensure_ascii=False,indent=2)+'\n')
 (out/'logcat.txt').write_text(adb('logcat','-d'))
