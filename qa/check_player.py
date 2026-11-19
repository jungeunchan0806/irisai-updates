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
    return subprocess.check_output(['adb', *map(str, args)], timeout=90).decode(errors='replace').strip()

def ui():
    raw=adb('shell','am','instrument','-w','dev.iris.inspect/.Inspect')
    line=next((l for l in raw.splitlines() if l.startswith('INSTRUMENTATION_RESULT: snapshot=')),None)
    if line is None:raise RuntimeError(raw[-1800:])
    nodes=json.loads(line.split('=',1)[1])
    (OUT/'last-ui.json').write_text(json.dumps(nodes,ensure_ascii=False,indent=2))
    return nodes

def find(predicate, timeout=40):
    waited=False
    until = time.monotonic()+timeout
    while time.monotonic()<until:
        try:
            nodes=ui()
            if not waited and any(n['package']=='android' and n['text']=="Process system isn't responding" for n in nodes):
                action=next(n for n in nodes if n['package']=='android' and n['text']=='Wait')
                print('System server boot alert displayed; selecting Wait',flush=True)
                tap(action);waited=True;continue
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
    tap(item('IrisAI 설정'))
    item('플레이 설정')

def hud_count():
    return logs('IrisArena:I').count('hud_touch')

def samples():
    lines=logs('IrisExternal:I')
    return [dict(zip(('capture','track','full','latency_ms'),map(float,row))) for row in
            re.findall(r'capture=([\d.]+) track=([\d.]+) full=([\d.]+) latency_ms=(\d+)',lines)]

def snapshot(filename):
    (OUT/filename).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p']))

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
    adb('install',str(next(Path('build/outputs/apk/debug').glob('*.apk'))))
    adb('install',str(next(Path('qa/arena/build/outputs/apk/debug').glob('*.apk'))))
    adb('install',str(next(Path('qa/probe/build/outputs/apk/debug').glob('*.apk'))))
    adb('shell','input','keyevent','KEYCODE_WAKEUP')
    adb('shell','wm','dismiss-keyguard')
    adb('shell','wm','size','720x1280')
    adb('shell','wm','density','260')
    adb('shell','appops','set',APP,'SYSTEM_ALERT_WINDOW','allow')
    adb('shell','settings','put','secure','enabled_accessibility_services',APP+'/com.irisai.farm.TouchService')
    adb('shell','settings','put','secure','accessibility_enabled','1')
    adb('shell','logcat','-c')
    print(adb('shell','am','start','-W','-n',APP+'/com.irisai.farm.MainActivity'),flush=True)
    tap(item('화면 공유 시작'))
    # Only Android's projection dialogue can approve this session.
    def consent(n):
        return n.get('package') in ('com.android.systemui','com.google.android.permissioncontroller','com.android.permissioncontroller','android') and (
            n.get('resource-id')=='android:id/button1' or n.get('text','').lower() in ('start now','start recording','start','share screen','share'))
    tap(find(consent))
    wait_for(lambda: 'dev.iris.fixture.Arena' in adb('shell','dumpsys','activity','activities') and bool(re.findall(r'gear_center=',logs('IrisExternal:D'))))
    wait_for(lambda: 'fixture_ready' in logs('IrisArena:I'),20)
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
    wait_for(lambda: hud_count()>=3,120)
    wait_for(lambda: any(s['track']>0 and s['full']>0 for s in samples()),100)
    time.sleep(4)
    result={'api':adb('shell','getprop','ro.build.version.sdk'),'abi':adb('shell','getprop','ro.product.cpu.abi'),
            'fixture':'offline recorded screen','model_input':'held_turn.jpg','brawler':'8bit','fps_samples':samples(),
            'hud_touches_before_pause':hud_count(),'android_consent':True}
    open_gear()
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
    print([n for n in ui() if n['text'] or n['content-desc']][:22],flush=True)
    raise
finally:
    (OUT/'app-log.txt').write_text(logs('IrisExternal:D','IrisArena:I','AndroidRuntime:E','MediaProjectionManagerService:I','WindowManager:W'))
    (OUT/'windows.txt').write_text(adb('shell','dumpsys','window','windows'))
    snapshot('last-screen.png')
