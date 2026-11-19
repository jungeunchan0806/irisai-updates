import json,time,subprocess
from device_qa import ROOT,OUT,P,adb,find,launch,view,nodes,tap,passed,results,snapshot
def check():
    print('API',adb('shell','getprop','ro.build.version.sdk'),'ABI',adb('shell','getprop','ro.product.cpu.abilist'),flush=True)
    for _ in range(60):
        if 'true' in adb('shell','cmd','user','is-user-unlocked','0',check=False) and 'package:' in adb('shell','pm','path','android',check=False):break
        time.sleep(2)
    print(adb('shell','df','-h','/data'),flush=True)
    def install(file):
        for attempt in range(4):
            p=subprocess.run(['adb','install','--no-streaming','-r',file],capture_output=True,text=True)
            print('install',file,p.stdout.strip(),p.stderr.strip(),flush=True)
            if p.returncode==0:return
            time.sleep(4)
        raise AssertionError('Could not install '+file)
    install(str(ROOT/'theseven-release/TheSeven-Autofarm-2.0.1.apk'))
    passed('Android 17 signed APK installed')
    install('/tmp/theseven-probe.apk')
    adb('shell','wm','size','640x1100');adb('shell','wm','density','210')
    launch()
    find('나중에',True,tries=18)
    find('더세븐 오토팜');find('일반 설정',True,scroll=True)
    adb('shell','appops','set',P,'SYSTEM_ALERT_WINDOW','allow')
    find('작은 톱니바퀴 표시',True,scroll=True);find('빠른 설정 열기',True)
    find('브롤러 예약');find('플레이스타일 ·',True);find('지원',True);find('플레이스타일 · 지원')
    snapshot('quick');passed('Android 17 external settings gear opens, edits style')
    adb('shell','am','force-stop',P);launch();find('나중에',True,tries=18);find('플레이스타일 · 지원')
    find('연습 시작',True);find('독립 연습장');time.sleep(3);snapshot('arena');passed('Android 17 retains style, starts offline practice')
    assert 'Process: '+P not in adb('shell','logcat','-b','crash','-d','-s','AndroidRuntime:E')
try:check()
finally:
    (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    (OUT/'crashes.txt').write_text(adb('shell','logcat','-b','crash','-d','-s','AndroidRuntime:E'))
    snapshot('last')
