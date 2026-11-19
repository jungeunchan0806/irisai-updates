"""Exercise native updates through the real Android Package Installer on an isolated emulator."""
import json
import time
from device_qa import ROOT, OUT, P, adb, find, launch, nodes, tap, passed, results, put_prefs, prefs, snapshot
def native_update():
    adb('install','/tmp/theseven-probe.apk');adb('root');adb('wait-for-device')
    adb('shell','wm','size','640x1100');adb('shell','wm','density','210')
    print('API',adb('shell','getprop','ro.build.version.sdk'),'ABI',adb('shell','getprop','ro.product.cpu.abilist'),flush=True)
    adb('install',str(ROOT/'theseven-release/TheSeven-Autofarm-2.0.0.apk'))
    put_prefs('<map><string name="brawler">colt</string><boolean name="repeat" value="true"/></map>')
    adb('shell','appops','set',P,'REQUEST_INSTALL_PACKAGES','allow')
    before=adb('shell','dumpsys','package',P);assert 'versionCode=4 ' in before
    launch();find('업데이트 확인',tries=22)
    # Use the button in the application's dialog, then accept Android's distinct installation prompt.
    for n in nodes():
        if n.get('text')=='업데이트':tap(n);break
    else:raise AssertionError('Missing native update confirmation')
    deadline=time.monotonic()+70
    accepted=False
    while time.monotonic()<deadline:
        tree=nodes()
        for node in tree:
            owner=node.get('package','')
            if 'packageinstaller' in owner and node.get('text','').lower() in ('update','install','업데이트','설치'):
                snapshot('native-installer');tap(node);accepted=True;break
        if accepted:break
        time.sleep(1)
    assert accepted,[(n.get('package'),n.get('text')) for n in tree]
    for _ in range(40):
        if 'versionCode=5 ' in adb('shell','dumpsys','package',P):break
        time.sleep(1)
    else:raise AssertionError('Package version remained 4')
    assert prefs()['brawler']=='colt' and prefs()['repeat']=='true'
    passed('Android system installer replaced signed 2.0.0 from within app with 2.0.1 and retained preferences')
    adb('shell','am','force-stop',P);launch();find('업데이트 확인',tries=18)
    for n in nodes():
        if n.get('text')=='업데이트':tap(n);break
    find('Colt');find('일반 설정',True,scroll=True);find('연습 데이터 v4',scroll=True)
    snapshot('data-installed');passed('Android 12L signed data v4 installed after native update')
try:native_update()
finally:
    (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    (OUT/'crashes.txt').write_text(adb('shell','logcat','-b','crash','-d','-s','AndroidRuntime:E'))
    snapshot('last')
