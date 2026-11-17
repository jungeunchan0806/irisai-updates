"""Exercise the production JS-to-DEX bridge on Android, not a Java-only UI mock."""
from pathlib import Path
import json, subprocess, time, frida

out=Path('bridgecheck-results');out.mkdir(exist_ok=True)
subprocess.run(['adb','install','-r','bshackcheck/build/outputs/apk/debug/bshackcheck-debug.apk'],check=True)
subprocess.run(['adb','shell','am','start','-n','com.irisai.bshack.check/com.supercell.brawlstars.GameApp'],check=True)
time.sleep(2)
device=frida.get_usb_device(timeout=15)
pid=int(subprocess.check_output(['adb','shell','pidof','com.irisai.bshack.check'],text=True).strip())
session=device.attach(pid)
source=Path('bridgecheck/java-bridge.js').read_text()+'''
const _st={started:true,disposed:false,requested:null};
const _ag={enabled:false,phase:'idle'};
const _MS={checked:0};
const _license={status(){return {ready:true,checked:true,active:true};},
 isActive(){return true;},tap(){_panelActivate();},copy(){},bindView(){}};
'''+Path('bshack/engine_state.js').read_text()+Path('bshack/panel_bridge.js').read_text()+'''
rpc.exports={state(){return {enabled:_ag.enabled,error:_panelError,phase:_panelPhase(),foreground:_panelForeground};},
 click(){return new Promise((resolve,reject)=>_Java.perform(()=>_Java.scheduleOnMainThread(()=>{try{
 const records=_Java.use('android.app.ActivityThread').currentActivityThread().mActivities.value;
 const it=records.values().iterator();
 while(it.hasNext()){
  const r=_Java.cast(it.next(),_Java.use('android.app.ActivityThread$ActivityClientRecord'));
  if(r.paused.value)continue;
  function find(v){
   const d=v.getContentDescription();if(d&&String(d)==='오토골 켜기 또는 끄기'){v.performClick();return true;}
   if(_Java.use('android.view.ViewGroup').class.isInstance(v)){
    const g=_Java.cast(v,_Java.use('android.view.ViewGroup'));
    for(let i=0;i<g.getChildCount();i++)if(find(g.getChildAt(i)))return true;
   }return false;
  }
  if(find(r.activity.value.getWindow().getDecorView())){resolve(true);return;}
 }resolve(false);
 }catch(e){reject(String(e));}})));}};
'''
script=session.create_script(source,runtime='qjs')
messages=[]
script.on('message',lambda message,data:messages.append(message))
script.load()
try:
 deadline=time.monotonic()+15
 state={}
 while time.monotonic()<deadline:
  state=script.exports_sync.state()
  if state['foreground']:break
  time.sleep(.25)
 print('Attached:',json.dumps(state))
 assert state['foreground'],state
 assert script.exports_sync.click(),'Toggle view absent'
 time.sleep(1)
 state=script.exports_sync.state();print('After ON:',json.dumps(state))
 assert state['enabled'] and not state['error'],state
 assert script.exports_sync.click()
 time.sleep(.5)
 state=script.exports_sync.state();print('After OFF:',json.dumps(state))
 assert not state['enabled'] and not state['error'],state
 print('PASS real Frida Java bridge ON/OFF')
finally:
 (out/'messages.json').write_text(json.dumps(messages,ensure_ascii=False,indent=2))
 (out/'logcat.txt').write_text(subprocess.check_output(['adb','logcat','-d','-t','1500'],text=True))
 print(json.dumps(messages[-10:],ensure_ascii=False))
 session.detach()
