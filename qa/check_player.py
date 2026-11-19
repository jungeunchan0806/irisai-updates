"""Use a blank emulator and an offline battle-image fixture for the external player flow."""
import json
import re
import subprocess
import time
from pathlib import Path

APP = 'com.irisai.externalplayer'
FIXTURE = 'com.supercell.brawlstars'
OUT = Path('qa-results')
OUT.mkdir(exist_ok=True)

def adb(*args):
    result=subprocess.run(['adb',*map(str,args)],capture_output=True,text=True,errors='replace',timeout=90)
    if result.returncode:
        print('adb',args, result.stdout[-1200:],result.stderr[-1200:],flush=True)
        result.check_returncode()
    return result.stdout.strip()

def ui():
    for attempt in range(3):
        raw=adb('shell','am','instrument','-w','dev.iris.inspect/.Inspect')
        line=next((l for l in raw.splitlines() if l.startswith('INSTRUMENTATION_RESULT: snapshot=')),None)
        if line is not None:break
        if 'shortMsg=Process crashed' not in raw or attempt==2:raise RuntimeError(raw[-1800:])
        print('Restarting the isolated inspection process',flush=True)
        time.sleep(1)
    nodes=json.loads(line.split('=',1)[1])
    (OUT/'last-ui.json').write_text(json.dumps(nodes,ensure_ascii=False,indent=2))
    return nodes

def find(predicate, timeout=40):
    handled=set()
    until = time.monotonic()+timeout
    while time.monotonic()<until:
        try:
            nodes=ui()
            tutorial=next((n for n in nodes if n['package'] in ('android','com.android.systemui') and n['text']=='Got it'),None)
            if tutorial and any(n['package']==tutorial['package'] and n['text']=='Viewing full screen' for n in nodes):
                tap(tutorial);continue
            startup_alert=next((n['text'] for n in nodes if n['package']=='android' and n['text'] in (
                "Process system isn't responding","Pixel Launcher isn't responding","Launcher isn't responding","System UI isn't responding")),None)
            if startup_alert and startup_alert not in handled:
                handled.add(startup_alert)
                dismiss="Close app" if "Launcher" in startup_alert else "Wait"
                print("Emulator boot alert",startup_alert,dismiss,flush=True)
                tap(next(n for n in nodes if n['package']=='android' and n['text']==dismiss))
                continue
            for node in nodes:
                if predicate(node): return node
        except subprocess.CalledProcessError: pass
        time.sleep(.35)
    print([(n['package'],n['text'],n['content-desc'],n['enabled']) for n in ui() if n['text'] or n['content-desc']],flush=True)
    raise AssertionError('Expected Android UI element was not visible')

def item(label):
    return find(lambda n: label in (n.get('text'), n.get('content-desc')) and n.get('enabled')=='true')

def tap(node):
    l,t,r,b = map(int,re.findall(r'-?\d+',node['bounds']))
    adb('shell','input','tap',(l+r)//2,(t+b)//2)
    time.sleep(.5)

def logs(*tags):
    return adb('logcat','-d','-s',*tags)

def wait_for(probe, timeout=60):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=probe()
        if value: return value
        time.sleep(.55)
    raise AssertionError('Timed out waiting for '+probe.__name__)

def open_gear():
    gear=item('IrisAI 설정')
    l,t,r,b=map(int,re.findall(r'-?\d+',gear['bounds']))
    adb('shell','input','swipe',(l+r)//2,(t+b)//2,(l+r)//2,(t+b)//2,100)
    item('플레이 설정')

def hud_count():
    return logs('IrisArena:I').count('hud_touch')

def samples():
    lines=logs('IrisExternal:I')
    return [dict(zip(('capture','track','full','latency_ms'),map(float,row))) for row in
            re.findall(r'capture=([\d.]+) track=([\d.]+) full=([\d.]+) latency_ms=(\d+)',lines)]

def snapshot(filename):
    (OUT/filename).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p']))

def install(*args):
    for attempt in range(3):
        result=subprocess.run(['adb','install','--no-incremental','--no-streaming','-r',*args],capture_output=True,text=True,timeout=130)
        message=result.stdout+' '+result.stderr
        if result.returncode==0 and 'Success' in message:return
        print(message,flush=True)
        transient=('Broken pipe',"Can't find service",'device offline','cmd: Failure','device not found','DeadObjectException')
        if not any(m in message for m in transient) or attempt==2:raise AssertionError(message)
        time.sleep(10)
        wait_for(lambda: 'package:' in adb('shell','pm','path','android'),90)

def stable_boot():
    previous=None
    start=time.monotonic()
    steady=start
    while time.monotonic()-start<135:
        try:
            pid=adb('shell','pidof','system_server')
            ready=all('found' in adb('shell','service','check',service) and
                      'not found' not in adb('shell','service','check',service)
                      for service in ('package','window','accessibility'))
            if not ready or not pid or previous!=pid: steady=time.monotonic()
            previous=pid
            if time.monotonic()-steady>=18:return
        except subprocess.CalledProcessError:steady=time.monotonic()
        time.sleep(2)
    raise RuntimeError('Android system services did not settle after boot')

def run():
    stable_boot()
    abis=adb('shell','getprop','ro.product.cpu.abilist').split(',')
    signed='arm64-v8a' in abis
    if signed:
        install('--abi','arm64-v8a',str(next(Path('dist').glob('*.apk'))))
    else:
        install(str(next(Path('build/outputs/apk/debug').glob('*.apk'))))
    path=adb('shell','pm','path',APP)
    assert path.startswith('package:'),path
    print('installed player '+path,flush=True)
    print('release_apk='+str(signed)+' device_abis='+','.join(abis),flush=True)
    install(str(next(Path('qa/arena/build/outputs/apk/debug').glob('*.apk'))))
    install(str(next(Path('qa/probe/build/outputs/apk/debug').glob('*.apk'))))
    adb('shell','input','keyevent','KEYCODE_WAKEUP')
    adb('shell','wm','dismiss-keyguard')
    adb('shell','wm','size','720x1280')
    adb('shell','wm','density','260')
    adb('shell','appops','set',APP,'SYSTEM_ALERT_WINDOW','allow')
    adb('shell','settings','put','secure','enabled_accessibility_services',APP+'/com.irisai.farm.TouchService')
    adb('shell','settings','put','secure','accessibility_enabled','1')
    print('Installed third-party packages:',adb('shell','pm','list','packages','-3'),flush=True)
    adb('shell','logcat','-c')
    print(adb('shell','am','start','-W','-n',APP+'/com.irisai.farm.MainActivity'),flush=True)
    tap(item('화면 공유 시작'))
    # Only Android's projection dialogue can approve this session.
    def consent(n):
        return n.get('package') in ('com.android.systemui','com.google.android.permissioncontroller','com.android.permissioncontroller','android') and (
            n.get('resource-id')=='android:id/button1' or n.get('text','').lower() in ('start now','start recording','start','share screen','share'))
    tap(find(consent))
    wait_for(lambda: 'fixture_ready' in logs('IrisArena:I'),30)
    print('Screen sharing active and offline arena opened',flush=True)
    time.sleep(2)
    open_gear()
    tap(item('브롤러 선택'))
    tap(find(lambda n: n.get('class')=='android.widget.TextView' and n.get('text')=='8bit'))
    selected=item('브롤러 선택')
    assert selected['text']=='8bit',selected
    # On a short landscape display the native scroll view reveals the action on swipe.
    def button(label):
        visible=ui()
        found=next((n for n in visible if n.get('text')==label and n.get('enabled')=='true'),None)
        if found:return found
        scroll=next(n for n in visible if n.get('class')=='android.widget.ScrollView')
        l,t,r,b=map(int,re.findall(r'-?\d+',scroll['bounds']))
        adb('shell','input','swipe',(l+r)//2,b-30,(l+r)//2,t+20,280)
        return item(label)
    snapshot('settings.png')
    tap(button('선택한 브롤러로 시작'))
    try:
        wait_for(lambda: hud_count()>=3,300 if signed else 120)
    except AssertionError:
        open_gear()
        print('Player status after the touch timeout:',[n['text'] for n in ui() if n['package']==APP],flush=True)
        raise
    time.sleep(10)
    result={'api':adb('shell','getprop','ro.build.version.sdk'),'abi':adb('shell','getprop','ro.product.cpu.abi'),
            'fixture':'offline recorded screen','model_input':'held_turn.jpg','brawler':'8bit','fps_samples':samples(),
            'hud_touches_before_pause':hud_count(),'android_consent':True}
    open_gear()
    measured=find(lambda n:'최근 실측 FPS' in n['text'])['text']
    result.update(signed_release_apk=signed, supported_abis=abis, measured_menu=measured,
                  qemu_vsync=adb('shell','getprop','ro.boot.qemu.vsync'))
    print(json.dumps(result,ensure_ascii=False),flush=True)
    numbers=re.search(r'캡처 ([\d.]+) +· +추적 ([\d.]+) +· +전체 분석 ([\d.]+)',measured)
    assert numbers and float(numbers[1])>0,measured
    count=hud_count();time.sleep(2);assert hud_count()==count,'Input continued in menu'
    tap(button('플레이 정지'))
    tap(button('닫기') if any(n.get('text')=='닫기' for n in ui()) else item_after_scroll_top())
    count=hud_count();time.sleep(2);assert hud_count()==count,'Input continued after stop'
    open_gear()
    tap(button('화면 공유 종료'))
    wait_for(lambda: 'IrisAI 설정 버튼' not in adb('shell','dumpsys','window','windows'),15)
    result.update(menu_pause=True,stop=True,projection_ended=True)
    (OUT/'results.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result),flush=True)

def item_after_scroll_top():
    scroll=next(n for n in ui() if n.get('class')=='android.widget.ScrollView')
    l,t,r,b=map(int,re.findall(r'-?\d+',scroll['bounds']))
    adb('shell','input','swipe',(l+r)//2,t+30,(l+r)//2,b-20,290)
    return item('닫기')

try:
    run()
except BaseException:
    print(logs('IrisExternal:D','IrisArena:I','AndroidRuntime:E','Toast:W','AccessibilityManagerService:W')[-5500:],flush=True)
    print(adb('shell','dumpsys','accessibility')[:2200],flush=True)
    raise
finally:
    (OUT/'app-log.txt').write_text(logs('IrisExternal:D','IrisArena:I','AndroidRuntime:E','MediaProjectionManagerService:I','WindowManager:W'))
    (OUT/'windows.txt').write_text(adb('shell','dumpsys','window','windows'))
    snapshot('last-screen.png')
