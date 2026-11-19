import json,time
from device_qa import ROOT,OUT,P,adb,find,launch,view,nodes,tap,passed,results,snapshot
def check():
    print('API',adb('shell','getprop','ro.build.version.sdk'),'ABI',adb('shell','getprop','ro.product.cpu.abilist'),flush=True)
    adb('install','/tmp/theseven-probe.apk')
    adb('shell','wm','size','640x1100');adb('shell','wm','density','210')
    adb('install',str(ROOT/'theseven-release/TheSeven-Autofarm-2.0.1.apk'));launch()
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
