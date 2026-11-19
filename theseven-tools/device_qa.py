"""Run against a disposable, rooted Google APIs emulator. Uses only our own APKs."""
from pathlib import Path
import hashlib, json, re, subprocess, sys, time, urllib.request, xml.etree.ElementTree as ET

P='com.irisai.externalplayer'
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'theseven-qa-results'
OUT.mkdir(exist_ok=True)
def run(*args, check=True):
    return subprocess.run(args,text=True,capture_output=True,check=check,timeout=90).stdout.strip()
def adb(*args,**k): return run('adb',*args,**k)
def view(all_windows=False):
    # The framework XML dumper follows the focused dialog. The instrumented inspector also sees the separate overlay.
    if not all_windows:
        dumped=adb('shell','uiautomator','dump','/sdcard/theseven-window.xml',check=False)
        if 'dumped' in dumped.lower():
            raw=adb('shell','cat','/sdcard/theseven-window.xml',check=False)
            if '<?xml' in raw:
                try:return ET.fromstring(raw[raw.index('<?xml'):])
                except ET.ParseError:pass
    raw=adb('shell','am','instrument','-w','dev.theseven.inspect/.Inspect')
    xml=ET.Element('hierarchy')
    for line in raw.splitlines():
        if line.startswith('INSTRUMENTATION_RESULT: snapshot='):
            for n in json.loads(line.partition('=')[2]):ET.SubElement(xml,'node',n)
    return xml
def nodes(all_windows=False):return list(view(all_windows).iter('node'))
def tap(node):
    l,t,r,b=map(int,re.findall(r'\d+',node.attrib['bounds']))
    adb('shell','input','tap',str((l+r)//2),str((t+b)//2))
def find(label,click=False,scroll=False,tries=8):
    for attempt in range(tries):
        tree=nodes(all_windows=label=='빠른 설정 열기')
        for n in tree:
            if label in n.get('text','') or label in n.get('content-desc',''):
                if click:tap(n);time.sleep(.32)
                return n
        if scroll and attempt%2==0:adb('shell','input','swipe','455','900','455','330','360')
        time.sleep(.35)
    raise AssertionError('Missing '+label+' '+str([(n.get('text'),n.get('content-desc')) for n in tree]))
def launch():adb('shell','am','start','-n',P+'/com.irisai.farm.MainActivity')
def snapshot(name):
    (OUT/(name+'.xml')).write_bytes(ET.tostring(view(),encoding='utf-8'))
    with (OUT/(name+'.png')).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
def passed(s):print('PASS',s,flush=True);results.append(s)
def put_prefs(entry):
    path=f'/data/user/0/{P}/shared_prefs'
    shellid=adb('shell','stat','-c','%u',f'/data/user/0/{P}')
    assert shellid.isdigit()
    tmp=OUT/'seed.xml';tmp.write_text(entry)
    adb('push',str(tmp),'/data/local/tmp/theseven-seed.xml')
    adb('shell','mkdir','-p',path);adb('shell','cp','/data/local/tmp/theseven-seed.xml',path+'/player.xml')
    adb('shell','chown','-R',shellid+':'+shellid,path);adb('shell','restorecon','-RF',path)
def prefs():
    doc=ET.fromstring(adb('shell','cat',f'/data/user/0/{P}/shared_prefs/player.xml'))
    return {v.attrib['name']:v.attrib.get('value',v.text or '') for v in doc}
def scheduled_runs():
    # One transaction with a distant positive case, locked/missing IDs, and an already cancelled job.
    adb('shell','am','force-stop',P)
    doc=ET.fromstring(adb('shell','cat',f'/data/user/0/{P}/shared_prefs/player.xml'))
    def set_pref(k,value):
        node=next((v for v in doc if v.get('name')==k),None)
        if node is None:node=ET.SubElement(doc,'string',name=k)
        node.text=json.dumps(value,separators=(',',':'))
    now=int(time.time()*1000)
    existing=json.loads(next(v.text for v in doc if v.get('name')=='theseven.jobs'))
    for offset,(key,b,state) in enumerate([('ready','nori','pending'),('locked','wendy','pending'),('absent','fixture_unknown','pending'),('cancel','vince','cancelled')]):
        existing[key]={'due':now-10000+offset,'status':state,'config':{'brawler':b,'mode':'ball','map':'bush','style':'aggressive'},'choices':{'gear2':'shield'}}
    set_pref('theseven.jobs',existing)
    set_pref('theseven.inventory.nori',{'slots':2,'shield':True,'speed':True})
    set_pref('theseven.inventory.wendy',{'locked':True})
    put_prefs(ET.tostring(doc,encoding='unicode'))
    launch();find('독립 연습장')
    first=json.loads(prefs()['theseven.jobs'])
    assert first['ready']['status']=='done' and first['ready']['result']['id']=='nori'
    assert len(first['ready']['result']['pages'])>2 and first['ready']['result']['points']>0
    equip=first['ready']['result']['equipment']
    assert equip['gear1']['id']=='speed' and equip['gear2']['id']=='shield' and equip['gear2']['selection']=='shield'
    assert first['locked']['result']['error']=='잠긴 브롤러' and first['absent']['result']['error']=='목록에 없는 브롤러'
    assert first['cancel']['status']=='cancelled' and 'result' not in first['cancel']
    adb('shell','am','force-stop',P);launch();find('더세븐 오토팜')
    assert first==json.loads(prefs()['theseven.jobs'])
    passed('scheduled stable-ID selection, ownership/gear recalc, locked/missing/cancelled/once-only across restart')
def main():
    adb('install','/tmp/theseven-probe.apk')
    adb('root');adb('wait-for-device');assert adb('shell','id','-u')=='0'
    adb('shell','wm','size','640x1100');adb('shell','wm','density','210')
    abis=adb('shell','getprop','ro.product.cpu.abilist')
    print('API',adb('shell','getprop','ro.build.version.sdk'),'ABI',abis,flush=True)
    release=ROOT/'theseven-release';old=Path('/tmp/theseven-old.apk')
    urllib.request.urlretrieve('https://github.com/jungeunchan0806/irisai-updates/releases/download/android-iris-external-1.0.2/IrisAI-External-1.0.2-arm64.apk',old)
    expected=(ROOT/'theseven-tools/previous.sha256').read_text().strip()
    assert hashlib.sha256(old.read_bytes()).hexdigest()==expected
    if 'arm64-v8a' not in abis:
        print('Previous release native ABI unavailable; using package-manager ABI override for migration only',flush=True)
        adb('install','--abi','arm64-v8a',str(old))
    else:adb('install',str(old))
    put_prefs('<map><string name="brawler">colt</string><boolean name="repeat" value="true"/><string name="migration_sentinel">preserve</string></map>')
    adb('install','-r',str(release/'TheSeven-Autofarm-2.0.1.apk'));launch()
    # Dismiss optional signed update prompt before inspecting the preserved state.
    try:find('나중에',True,tries=2)
    except AssertionError:pass
    if any(n.get('text')=='나중에' for n in nodes()):find('나중에',True)
    find('더세븐 오토팜');find('Colt');v=prefs();assert v['brawler']=='colt' and v['repeat']=='true' and v['migration_sentinel']=='preserve'
    passed('1.0.2 signed release -> 2.0.1 install -r, UI loads and prior preferences intact');snapshot('home')
    find('일반 설정',True,scroll=True);find('업데이트 확인',True,scroll=True);find('업데이트 확인')
    find('업데이트',False)
    for n in nodes():
        if n.get('text')=='업데이트':tap(n);break
    find('연습 데이터 v4',scroll=True);passed('signed data patch applied through settings')
    adb('shell','am','force-stop',P);launch();find('일반 설정',True,scroll=True);find('연습 데이터 v4',scroll=True)
    passed('patched catalogue survives process restart')
    adb('shell','appops','set',P,'SYSTEM_ALERT_WINDOW','allow')
    adb('shell','input','keyevent','4');find('일반 설정',True,scroll=True)
    find('작은 톱니바퀴 표시',True,scroll=True);find('빠른 설정 열기',True)
    for key in ['브롤러 ·','모드 ·','플레이스타일 ·','브롤러 예약']:find(key)
    assert not any(n.get('text') in ['라이선스 등록','Discord','브롤러와 장비'] for n in nodes())
    snapshot('quick');find('플레이스타일 ·',True);find('지원',True);find('플레이스타일 · 지원')
    passed('real overlay gear, four quick menu items and shared settings')
    find('닫기',True);adb('shell','am','force-stop',P);launch();find('플레이스타일 · 지원');find('연습 시작',True);find('독립 연습장')
    time.sleep(2);snapshot('arena');passed('independent arena renders after restart')
    adb('shell','input','keyevent','4');find('브롤러 예약',True);find('예약 추가',True);find('예약 저장',True,scroll=True);find('예약 취소',True,scroll=True);find('취소');passed('reservation saved and cancelled through UI')
    adb('shell','input','keyevent','4');find('앱 정보',True,scroll=True);find('개발자: @asdf027949');find('Discord');snapshot('about');passed('Discord and developer visible')
    scheduled_runs()
    seller='com.theseven.seller';adb('install',str(release/'TheSeven-Seller-2.0.1.apk'));adb('shell','am','start','-n',seller+'/.Seller')
    find('판매자 기기 등록 필요',tries=12);snapshot('seller');passed('production API rejects unapproved Android Keystore seller')
    crashes=adb('shell','logcat','-b','crash','-d','-s','AndroidRuntime:E')
    assert 'Process: '+P not in crashes and 'Process: '+seller not in crashes
results=[]
try:main()
finally:
    (OUT/'android-crashes.txt').write_text(adb('shell','logcat','-b','crash','-d','-s','AndroidRuntime:E'))
    (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    snapshot('last')
